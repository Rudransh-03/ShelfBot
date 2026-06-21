// JWT signing + the middleware that protects /proxy/* routes.
//
// In #11.5 we moved off email-based identity entirely. The unit of identity
// is now the device — see db.js. JWT claims:
//
//   sub:  numeric devices.id   (PRIMARY KEY)
//   did:  device_id            (the machine fingerprint; for debug/log only)
//   plan: 'free' | 'pro'       (captured at token issuance — rate limit uses
//                              the LIVE plan from the DB row, so a plan
//                              upgrade takes effect immediately without
//                              waiting for token rotation)
//
// We never bind a user's identity (email/name/phone) into the JWT — there
// isn't one. If a person uses two machines they get two devices, each with
// their own quota. If they want to share a Pro plan across machines they
// activate the same license key on both (added in #12).

import jwt from 'jsonwebtoken'

export function makeAuth({ db, jwtSecret, jwtTtlSeconds }) {

  /** Registers a device (idempotent) and returns a fresh JWT bound to it. */
  function registerDevice(deviceId) {
    const device = db.upsertDevice(deviceId)
    const token  = jwt.sign(
      { sub: device.id, did: device.device_id, plan: device.plan },
      jwtSecret,
      { expiresIn: jwtTtlSeconds }
    )
    return { token, device, expiresIn: jwtTtlSeconds }
  }

  /**
   * Issues a JWT bound to a Google-backed account. The `typ:'acct'` claim is
   * what requireAuth uses to tell account tokens apart from the (typeless)
   * legacy device tokens, so the two identity models can coexist during the
   * migration to accounts-only.
   */
  function issueAccountToken(account) {
    const token = jwt.sign(
      { sub: account.id, typ: 'acct', email: account.email, plan: account.plan },
      jwtSecret,
      { expiresIn: jwtTtlSeconds }
    )
    return { token, account, expiresIn: jwtTtlSeconds }
  }

  /**
   * Express middleware. Accepts BOTH token shapes and always re-reads the live
   * row (so a plan upgrade applies immediately, not at token rotation):
   *   - account token (typ:'acct') → req.account + req.principal{kind:'account'}
   *   - device token  (legacy)     → req.device  + req.principal{kind:'device'}
   * req.principal is the unified handle the routes use for id + plan.
   */
  function requireAuth(req, res, next) {
    const header = req.headers.authorization || ''
    if (!header.startsWith('Bearer ')) {
      return res.status(401).json({ error: 'Missing bearer token' })
    }
    const token = header.slice('Bearer '.length).trim()
    try {
      const payload = jwt.verify(token, jwtSecret)
      if (payload.typ === 'acct') {
        const account = db.findAccountById(payload.sub)
        if (!account) return res.status(401).json({ error: 'Account no longer exists' })
        req.account   = account
        req.principal = { kind: 'account', id: account.id, plan: account.plan, account }
        return next()
      }
      // Legacy anonymous device token.
      const device = db.findDeviceById(payload.sub)
      if (!device) return res.status(401).json({ error: 'Device no longer registered' })
      req.device    = device
      req.principal = { kind: 'device', id: device.id, plan: device.plan, device }
      next()
    } catch (e) {
      return res.status(401).json({ error: 'Invalid or expired token: ' + e.message })
    }
  }

  return { registerDevice, issueAccountToken, requireAuth }
}
