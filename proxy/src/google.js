// Google OAuth: turn the authorization `code` the desktop app captured on its
// loopback redirect into a VERIFIED user profile { sub, email, emailVerified }.
//
// The desktop app does the PKCE browser dance and sends us {code, codeVerifier,
// redirectUri}. We finish it server-side so the client secret never ships in the
// app: exchange the code at Google's token endpoint, then cryptographically
// verify the returned id_token.
//
// Test seam: when GOOGLE_AUTH_STUB=1, `code` is treated as base64url(JSON
// {sub,email,emailVerified}) and returned directly — lets the proxy's account /
// trial logic be tested end-to-end with no network and no google-auth-library.
// NEVER set GOOGLE_AUTH_STUB in production (server.js logs a loud warning if it
// is on).

const GOOGLE_TOKEN_ENDPOINT = 'https://oauth2.googleapis.com/token'

export function isStubMode() {
  return process.env.GOOGLE_AUTH_STUB === '1'
}

export async function verifyGoogleCode({ code, codeVerifier, redirectUri, clientId, clientSecret }) {
  if (isStubMode()) {
    const json = JSON.parse(Buffer.from(String(code), 'base64url').toString('utf8'))
    return {
      sub:           String(json.sub),
      email:         String(json.email),
      emailVerified: json.emailVerified !== false,
    }
  }

  if (!clientId || !clientSecret) {
    throw new Error('GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET are not configured on the proxy')
  }

  // 1. Exchange the auth code (+ PKCE verifier) for tokens.
  const params = new URLSearchParams({
    code,
    client_id:     clientId,
    client_secret: clientSecret,
    redirect_uri:  redirectUri,
    grant_type:    'authorization_code',
    code_verifier: codeVerifier,
  })
  const r = await fetch(GOOGLE_TOKEN_ENDPOINT, {
    method:  'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body:    params,
  })
  if (!r.ok) {
    throw new Error('Google token exchange failed (' + r.status + '): ' + (await r.text()))
  }
  const tok = await r.json()
  if (!tok.id_token) throw new Error('Google did not return an id_token')

  // 2. Verify the id_token's signature + audience against Google's keys.
  //    Lazy-imported so the proxy (and the test stub path) don't require the
  //    package unless a real verification actually happens.
  const { OAuth2Client } = await import('google-auth-library')
  const client = new OAuth2Client(clientId)
  const ticket = await client.verifyIdToken({ idToken: tok.id_token, audience: clientId })
  const p = ticket.getPayload()
  if (!p) throw new Error('Google id_token had no payload')
  return {
    sub:           p.sub,
    email:         p.email,
    emailVerified: !!p.email_verified,
  }
}
