// Compatibility checks for the security-patched development tool dependency graph.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { randomUUID } from 'node:crypto'

const webRoot = fileURLToPath(new URL('../web/', import.meta.url))
const require = createRequire(new URL('../web/package.json', import.meta.url))
const { ESLint } = require('eslint')
const { globSync } = require('glob')
const { parseSync } = require('editorconfig')
const checker = new ESLint({ cwd: webRoot })
const [badApi] = await checker.lintText('export const courseId: any = 1\n', {
  filePath: 'src/api/course-lint-probe.ts'
})
assert(badApi.messages.some(item => item.ruleId === '@typescript-eslint/no-explicit-any' && item.severity === 2))
const [badVue] = await checker.lintText(
  '<script setup lang="ts">const props = defineProps<{ title: string }>(); props.title="changed"</script><template>{{ props.title }}</template>',
  { filePath: 'src/views/course/LintProbe.vue' }
)
assert(badVue.messages.some(item => item.ruleId === 'vue/no-mutating-props' && item.severity === 2))
assert(await checker.isPathIgnored(resolve(webRoot, 'src/contracts.d.ts')))
const files = globSync('src/**/*.{vue,ts}', { cwd: webRoot }).map(name => name.replaceAll('\\', '/'))
assert(files.includes('src/views/course/QaPage.vue'))
const fixture = fileURLToPath(new URL('../.local/lint-tools-' + randomUUID().slice(0, 10) + '/', import.meta.url))
mkdirSync(fixture, { recursive: true })
writeFileSync(resolve(fixture, '.editorconfig'), 'root=true\n[*.ts]\nindent_style=space\nindent_size=2\n')
const config = parseSync(resolve(fixture, 'sample.ts'))
assert.equal(config.indent_style, 'space')
assert.equal(Number(config.indent_size), 2)
console.log(JSON.stringify({ courseApiAnyRejected: true, vuePropMutationRejected: true, generatedTypesIgnored: true, globFindsCourseFiles: true, editorconfigPatternWorks: true }))
