// JWT signing/verification + the auth middleware that protects /proxy/* routes.
//
// For now we use a stub email-only login so the rest of the system can be
// validated end-to-end before Google OAuth credentials are provisioned. When
// you set up Google Cloud Console credentials, the change is:
//
//   1. Add /auth/google + /auth/google/callback routes that do the OAuth
//      dance with Google.
//   2. The callback verifies Google's id_token, extracts the email, and
//      calls `issueToken(email)` — the same function used here.
//
// Everything downstream of `issueToken` (DB, JWT format, middleware) is
// already production-shaped and won't change.

import jwt from 'jsonwebtoken'

export function makeAuth({ db, jwtSecret, jwtTtlSeconds }) {

  /** Creates or updates the user, signs and returns a JWT bound to them. */
  function issueToken(email) {
    const user = db.upsertUser(email)
    const token = jwt.sign(
      { sub: user.id, email: user.email },
      jwtSecret,
      { expiresIn: jwtTtlSeconds }
    )
    return { token, user, expiresIn: jwtTtlSeconds }
  }

  /** Express middleware: pulls JWT from Authorization header, attaches req.user. */
  function requireAuth(req, res, next) {
    const header = req.headers.authorization || ''
    if (!header.startsWith('Bearer ')) {
      return res.status(401).json({ error: 'Missing bearer token' })
    }
    const token = header.slice('Bearer '.length).trim()
    try {
      const payload = jwt.verify(token, jwtSecret)
      const user = db.findUserById(payload.sub)
      if (!user) return res.status(401).json({ error: 'User no longer exists' })
      req.user = user
      next()
    } catch (e) {
      // Invalid signature, expired, malformed — all surface as 401 so the
      // app knows to redirect to login.
      return res.status(401).json({ error: 'Invalid or expired token: ' + e.message })
    }
  }

  return { issueToken, requireAuth }
}
