import { describe, expect, it } from 'vitest'
import { validateAccount } from './account-validation'
const form = { username: 'student_1', password: 'abcdef', confirmation: 'abcdef', displayName: '' }
describe('account input boundaries', () => {
  it('accepts optional blank display name and a normal student registration', () => {
    expect(validateAccount(form, true)).toEqual({})
  })
  it.each(['ab', 'a'.repeat(33), '中文名', 'bad-name'])(
    'rejects registration username %s',
    username => {
      expect(validateAccount({ ...form, username }, true).username).toBeTruthy()
    }
  )
  it.each(['12345', 'a'.repeat(65), '中'.repeat(25), '😀'.repeat(19)])(
    'rejects password length/UTF-8 overflow',
    password => {
      expect(
        validateAccount({ ...form, password, confirmation: password }, true).password
      ).toBeTruthy()
    }
  )
  it('accepts precisely 72 UTF-8 bytes and checks confirmation and Unicode display-name length', () => {
    expect(
      validateAccount({ ...form, password: '中'.repeat(24), confirmation: '中'.repeat(24) }, true)
        .password
    ).toBeUndefined()
    expect(validateAccount({ ...form, confirmation: 'different' }, true).confirmation).toBeTruthy()
    expect(
      validateAccount({ ...form, displayName: '😀'.repeat(65) }, true).displayName
    ).toBeTruthy()
  })
  it('does not apply new registration constraints to an existing login', () => {
    expect(validateAccount({ ...form, username: 'a', password: 'x' }, false)).toEqual({})
  })
})
