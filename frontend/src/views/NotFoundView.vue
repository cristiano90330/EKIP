<!-- 显示未知路径的提示，并提供返回应用入口的导航。 -->

<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<template>
  <div class="not-found">
    <h2>404</h2>
    <p class="muted">页面不存在或已被移除</p>
    <RouterLink to="/overview" class="back">回到总览</RouterLink>
  </div>
</template>

<style scoped>
.not-found {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  min-height: 60vh;
  text-align: center;
}

.not-found h2 {
  margin: 0;
  font-size: 48px;
  color: var(--accent);
}

.not-found p {
  margin: 0;
}

.back {
  padding: 8px 16px;
  border-radius: var(--radius-full);
  background: var(--accent);
  color: var(--on-accent-ink);
  font-size: var(--fs-base);
  text-decoration: none;
}

.back:hover {
  background: var(--accent-dim);
  text-decoration: none;
}
</style>
