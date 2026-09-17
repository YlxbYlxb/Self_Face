<script setup>
// 备案号在构建期注入，见 frontend/.env.production（该文件不入库，样例见 .env.production.example）。
//
// 设计成「没配就不渲染」是有意的：本地开发和备案审核期间都不该出现空占位，
// 拿到备案号后填进 .env.production 重新构建即可，不需要改代码。
const beian = (import.meta.env.VITE_ICP_BEIAN || '').trim()

// 工信部要求备案号必须链接到备案管理系统
const link = import.meta.env.VITE_ICP_BEIAN_URL || 'https://beian.miit.gov.cn/'
</script>

<template>
  <footer v-if="beian" class="app-footer">
    <a :href="link" target="_blank" rel="noopener noreferrer nofollow">{{ beian }}</a>
  </footer>
</template>

<style scoped>
.app-footer {
  padding: 14px 20px 18px;
  text-align: center;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ink-soft);
}

.app-footer a {
  color: var(--ink-soft);
}

.app-footer a:hover {
  color: var(--brand);
}
</style>
