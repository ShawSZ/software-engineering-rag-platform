import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import MarkdownBody from './MarkdownBody.vue'

describe('rendered answer markdown', () => {
  it('preserves readable formatting and citations and clears a previous answer', async () => {
    const ui = mount(MarkdownBody, {
      props: { content: '# 课程回答\n\n**相关职责**应放在同一模块。[S1]\n\n[资料](https://example.test/material)' }
    })
    expect(ui.get('h1').text()).toBe('课程回答')
    expect(ui.get('strong').text()).toBe('相关职责')
    expect(ui.text()).toContain('[S1]')
    expect(ui.get('a').attributes('href')).toBe('https://example.test/material')
    await ui.setProps({ content: '' })
    expect(ui.text()).toBe('')
    expect(ui.find('a').exists()).toBe(false)
    ui.unmount()
  })

  it('removes executable HTML and unsafe links from a model answer', () => {
    const ui = mount(MarkdownBody, {
      props: {
        content: '可核验的回答。[S1]\n\n<script>window.modelAttempt = true</script>' +
          '<img src="x" onerror="alert(9137)">' +
          '<a href="javascript:alert(9137)">危险链接</a>' +
          '<svg onload="alert(9137)"><a xlink:href="javascript:alert(9137)">SVG</a></svg>'
      }
    })
    expect(ui.text()).toContain('可核验的回答。[S1]')
    expect(ui.find('script').exists()).toBe(false)
    for (const element of ui.element.querySelectorAll('*')) {
      for (const attribute of element.attributes) {
        expect(attribute.name).not.toMatch(/^on/i)
        if (/^(?:href|src|xlink:href)$/i.test(attribute.name)) {
          expect(attribute.value.trim()).not.toMatch(/^javascript:/i)
        }
      }
    }
    ui.unmount()
  })
})
