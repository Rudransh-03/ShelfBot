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

  /** Express middleware. On success, attaches req.device with the live row. */
  function requireAuth(req, res, next) {
    const header = req.headers.authorization || ''
    if (!header.startsWith('Bearer ')) {
      return res.status(401).json({ error: 'Missing bearer token' })
    }
    const token = header.slice('Bearer '.length).trim()
    try {
      const payload = jwt.verify(token, jwtSecret)
      // Always re-read the device row so the live plan (post-upgrade)
      // is used for rate limiting, not the value frozen in the token.
      const device = db.findDeviceById(payload.sub)
      if (!device) return res.status(401).json({ error: 'Device no longer registered' })
      req.device = device
      next()
    } catch (e) {
      return res.status(401).json({ error: 'Invalid or expired token: ' + e.message })
    }
  }

  return { registerDevice, requireAuth }
}
