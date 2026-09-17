<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import AppFooter from '../components/AppFooter.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const collapsed = ref(false)

const activeMenu = computed(() => route.path)

const menus = [
  { path: '/dashboard', title: '今日刷题', icon: 'Calendar' },
  { path: '/questions', title: '题库', icon: 'Collection' },
  { path: '/import', title: '导入题库', icon: 'Upload' },
  { path: '/wrong-book', title: '错题本', icon: 'Warning' },
  { path: '/resume', title: '简历分析', icon: 'Document' },
  { path: '/jd-match', title: 'JD 定向题单', icon: 'Aim' },
  { path: '/interview', title: '模拟面试', icon: 'ChatDotRound' },
  { path: '/settings', title: '设置', icon: 'Setting' }
]

function onSelect(path) {
  router.push(path)
}

function logout() {
  userStore.logout()
  router.push('/login')
}
</script>

<template>
  <el-container style="height: 100%">
    <el-aside :width="collapsed ? '64px' : '208px'" style="transition: width 0.2s">
      <div class="side">
        <div class="logo">
          <span class="dot" />
          <span v-if="!collapsed" class="logo-text">SelfFace</span>
        </div>
        <el-menu
          :default-active="activeMenu"
          :collapse="collapsed"
          :collapse-transition="false"
          style="border-right: none"
          @select="onSelect"
        >
          <el-menu-item v-for="m in menus" :key="m.path" :index="m.path">
            <el-icon><component :is="m.icon" /></el-icon>
            <template #title>{{ m.title }}</template>
          </el-menu-item>
        </el-menu>
      </div>
    </el-aside>

    <el-container>
      <el-header class="topbar">
        <div class="left">
          <el-icon class="collapse-btn" @click="collapsed = !collapsed">
            <Fold v-if="!collapsed" />
            <Expand v-else />
          </el-icon>
          <span class="crumb">{{ route.meta.title || '' }}</span>
        </div>
        <div class="right">
          <el-dropdown @command="(c) => c === 'logout' && logout()">
            <span class="user">
              <el-avatar :size="28" style="background: var(--brand)">
                {{ (userStore.displayName || 'U').slice(0, 1) }}
              </el-avatar>
              <span class="name">{{ userStore.displayName }}</span>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="settings" @click="router.push('/settings')">
                  个人与模型配置
                </el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main>
        <router-view />
      </el-main>

      <app-footer />
    </el-container>
  </el-container>
</template>

<style scoped>
.side {
  height: 100%;
  background: #fff;
  border-right: 1px solid var(--line);
  display: flex;
  flex-direction: column;
}

.logo {
  height: 60px;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 0 20px;
  border-bottom: 1px solid var(--line);
  white-space: nowrap;
  overflow: hidden;
}

.dot {
  width: 10px;
  height: 10px;
  border-radius: 3px;
  background: var(--brand);
  flex: none;
}

.logo-text {
  font-weight: 600;
  font-size: 15px;
  letter-spacing: 0.5px;
}

.topbar {
  height: 60px;
  background: #fff;
  border-bottom: 1px solid var(--line);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
}

.left {
  display: flex;
  align-items: center;
  gap: 14px;
}

.collapse-btn {
  font-size: 18px;
  cursor: pointer;
  color: var(--ink-soft);
}

.crumb {
  font-size: 15px;
  font-weight: 500;
}

.user {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  outline: none;
}

.name {
  font-size: 13px;
  color: var(--ink);
}

:deep(.el-main) {
  padding: 24px;
  overflow-y: auto;
}
</style>
