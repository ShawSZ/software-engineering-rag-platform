<template>
  <section>
    <h3>回答来源</h3>
    <div class="sources">
      <button
        v-for="source in sources"
        :key="source.sourceId"
        class="source-card"
        @click="show(source)"
      >
        <span class="source-label">{{ source.evidenceLabel }} · {{ source.documentTitle }}</span
        ><span
          >v{{ source.versionNumber }} ·
          {{
            source.pageNo === null
              ? `块 ${source.chunkIndex}`
              : `第 ${source.pageNo} 页 · 块 ${source.chunkIndex}`
          }}
          · {{ availability[source.availability] }}</span
        ><span class="excerpt">{{ source.excerpt }}</span
        ><span class="source-link">查看当时原文与定位 →</span>
      </button>
    </div>
    <PreviewDrawer v-model="open" :target="target" />
  </section>
</template>
<script setup lang="ts">
import { ref } from 'vue'
import type { Source } from '@/api/course'
import PreviewDrawer, { type PreviewTarget } from './PreviewDrawer.vue'
const props = defineProps<{ qaId: number; sources: Source[] }>()
const open = ref(false),
  target = ref<PreviewTarget>()
const availability = { CURRENT: '当前有效', UPDATED: '资料已更新', OFFLINE: '资料已下架' }
function show(source: Source) {
  target.value = { kind: 'source', qaId: props.qaId, sourceId: source.sourceId }
  open.value = true
}
</script>
<style scoped>
.sources {
  display: grid;
  gap: 12px;
}
.source-card {
  text-align: left;
  display: grid;
  gap: 8px;
  padding: 16px;
  border: 1px solid var(--fk-border);
  border-radius: 10px;
  background: var(--fk-card-bg);
  color: var(--fk-text-regular);
  cursor: pointer;
  font: inherit;
}
.source-card:hover {
  border-color: var(--fk-primary);
}
.source-label {
  font-weight: 600;
}
.excerpt {
  white-space: pre-wrap;
  line-height: 1.7;
  max-height: 120px;
  overflow: hidden;
}
.source-link {
  color: var(--fk-primary);
  font-size: 13px;
}
</style>
