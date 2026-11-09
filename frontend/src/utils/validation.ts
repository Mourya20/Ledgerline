/**
 * Returns the byte length of a string when encoded in UTF-8
 */
export function getUtf8ByteLength(str: string): number {
  return new TextEncoder().encode(str).length;
}

export interface PasswordValidationResult {
  valid: boolean;
  error?: string;
}

/**
 * Validates registration password against backend constraints:
 * - Minimum 12 characters
 * - Maximum 72 UTF-8 bytes
 * - Matches confirmation password
 */
export function validateRegistrationPassword(
  password: string,
  confirmPassword?: string
): PasswordValidationResult {
  if (!password) {
    return { valid: false, error: 'Password is required.' };
  }

  if (password.length < 12) {
    return {
      valid: false,
      error: 'Password must be at least 12 characters long.',
    };
  }

  const byteLength = getUtf8ByteLength(password);
  if (byteLength > 72) {
    return {
      valid: false,
      error: `Password exceeds 72 UTF-8 bytes (currently ${byteLength} bytes).`,
    };
  }

  if (confirmPassword !== undefined && password !== confirmPassword) {
    return {
      valid: false,
      error: 'Passwords do not match.',
    };
  }

  return { valid: true };
}
