<script setup>
// 移除 app.use(ElementPlus, { locale }) 后，中文语言包改由 ConfigProvider 提供，
// 否则分页器、上传等内置文案会退回英文
import zhCn from 'element-plus/es/locale/lang/zh-cn'
</script>

<template>
  <el-config-provider :locale="zhCn">
    <router-view />
  </el-config-provider>
</template>
