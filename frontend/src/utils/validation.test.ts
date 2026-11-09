import { describe, it, expect } from 'vitest';
import { getUtf8ByteLength, validateRegistrationPassword } from './validation';

describe('validation utility', () => {
  describe('getUtf8ByteLength', () => {
    it('correctly calculates ASCII byte length', () => {
      expect(getUtf8ByteLength('password1234')).toBe(12);
    });

    it('correctly calculates multi-byte UTF-8 characters', () => {
      // '₹' is 3 bytes in UTF-8
      expect(getUtf8ByteLength('₹')).toBe(3);
      // '€' is 3 bytes in UTF-8
      expect(getUtf8ByteLength('€')).toBe(3);
      // Emoji 🔐 is 4 bytes in UTF-8
      expect(getUtf8ByteLength('🔐')).toBe(4);
    });
  });

  describe('validateRegistrationPassword', () => {
    it('rejects passwords shorter than 12 characters', () => {
      const res = validateRegistrationPassword('shortPass1!', 'shortPass1!');
      expect(res.valid).toBe(false);
      expect(res.error).toBe('Password must be at least 12 characters long.');
    });

    it('accepts valid 12-character ASCII passwords', () => {
      const res = validateRegistrationPassword('ValidPass123!', 'ValidPass123!');
      expect(res.valid).toBe(true);
      expect(res.error).toBeUndefined();
    });

    it('rejects passwords exceeding 72 UTF-8 bytes with multi-byte characters', () => {
      // 25 euro symbols * 3 bytes = 75 bytes (length is 25 chars, but 75 bytes!)
      const multiBytePass = '€'.repeat(25);
      expect(multiBytePass.length).toBe(25); // >= 12 chars
      expect(getUtf8ByteLength(multiBytePass)).toBe(75); // > 72 bytes

      const res = validateRegistrationPassword(multiBytePass, multiBytePass);
      expect(res.valid).toBe(false);
      expect(res.error).toContain('Password exceeds 72 UTF-8 bytes');
    });

    it('rejects mismatched confirmation password', () => {
      const res = validateRegistrationPassword('ValidPass123!', 'DifferentPass!');
      expect(res.valid).toBe(false);
      expect(res.error).toBe('Passwords do not match.');
    });

    it('accepts boundary 72 UTF-8 byte passwords', () => {
      const exact72Bytes = 'A'.repeat(72);
      expect(getUtf8ByteLength(exact72Bytes)).toBe(72);

      const res = validateRegistrationPassword(exact72Bytes, exact72Bytes);
      expect(res.valid).toBe(true);
    });
  });
});
