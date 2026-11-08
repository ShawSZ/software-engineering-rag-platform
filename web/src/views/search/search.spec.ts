import { ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
const fixture = vi.hoisted(() => ({ mutate: vi.fn() }))
vi.mock('element-plus', async importOriginal => ({
  ...(await importOriginal<typeof import('element-plus')>()),
  ElMessage: { error: vi.fn(), warning: vi.fn() }
}))
vi.mock('@/components/async', () => ({ DocumentPreviewDrawer: { template: '<span />' } }))
vi.mock('@/composables/queries/useSearch', () => ({
  useSearchMutation: () => ({ isPending: ref(false), data: ref([]), mutateAsync: fixture.mutate })
}))
import SearchPage from './index.vue'

describe('search controlled input', () => {
  it('edits the local query and sends it only on search without mutating a child prop', async () => {
    fixture.mutate.mockResolvedValue([])
    const Input = {
      props: { modelValue: String },
      emits: ['update:modelValue'],
      inheritAttrs: false,
      template:
        '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
    }
    const Button = {
      emits: ['click'],
      template: '<button @click="$emit(\'click\')"><slot /></button>'
    }
    const Kb = { props: { modelValue: Number }, emits: ['update:modelValue'], template: '<span />' }
    const wrapper = mount(SearchPage, {
      shallow: true,
      global: { stubs: { ElInput: Input, ElButton: Button, KbSelect: Kb } }
    })
    wrapper.findComponent(Kb).vm.$emit('update:modelValue', 9)
    await wrapper.find('input').setValue('  local text  ')
    expect(fixture.mutate).not.toHaveBeenCalled()
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(fixture.mutate).toHaveBeenCalledWith({
      kbId: 9,
      query: 'local text',
      topK: 10,
      docType: undefined
    })
    await wrapper.find('input').setValue('second value')
    expect(fixture.mutate).toHaveBeenCalledOnce()
    wrapper.unmount()
  })
})
