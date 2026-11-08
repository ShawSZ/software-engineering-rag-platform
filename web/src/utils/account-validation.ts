export interface AccountForm {
  username: string
  password: string
  displayName: string
  confirmation: string
}
export type AccountErrors = Partial<Record<keyof AccountForm, string>>
export function validateAccount(form: AccountForm, register: boolean): AccountErrors {
  const errors: AccountErrors = {}
  if (!form.username.trim()) errors.username = '请输入用户名'
  else if (register && !/^[A-Za-z0-9_]{3,32}$/.test(form.username.trim()))
    errors.username = '用户名须为 3—32 位字母、数字或下划线'
  if (!form.password) errors.password = '请输入密码'
  else if (
    register &&
    (Array.from(form.password).length < 6 ||
      Array.from(form.password).length > 64 ||
      new TextEncoder().encode(form.password).length > 72)
  )
    errors.password = '密码须为 6—64 个字符，且 UTF-8 编码不超过 72 字节'
  if (register && form.displayName.trim() && Array.from(form.displayName.trim()).length > 64)
    errors.displayName = '显示名称最多 64 个字符'
  if (register && form.confirmation !== form.password) errors.confirmation = '两次密码不一致'
  return errors
}
