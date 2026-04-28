import { cookies } from 'next/headers';
import { SignJWT, jwtVerify } from 'jose';

const COOKIE_NAME = 'vd_session';
const DEFAULT_SECRET = 'change-me-in-.env';

function getSecret() {
  const secret = process.env.AUTH_SECRET || DEFAULT_SECRET;
  return new TextEncoder().encode(secret);
}

export async function setSessionCookie(uid: string, email: string, name: string) {
  const token = await new SignJWT({ uid, email, name })
    .setProtectedHeader({ alg: 'HS256' })
    .setExpirationTime('30d')
    .sign(getSecret());

  cookies().set(COOKIE_NAME, token, {
    httpOnly: true,
    sameSite: 'lax',
    secure: process.env.NODE_ENV === 'production',
    path: '/',
    maxAge: 60 * 60 * 24 * 30
  });
}

export async function readSession() {
  const token = cookies().get(COOKIE_NAME)?.value;
  if (!token) return null;
  try {
    const { payload } = await jwtVerify(token, getSecret());
    const { uid, email, name } = payload as { uid: string; email: string; name: string };
    return { uid, email, name };
  } catch {
    cookies().delete(COOKIE_NAME);
    return null;
  }
}

export function clearSession() {
  cookies().delete(COOKIE_NAME);
}
