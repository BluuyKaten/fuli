<template>
  <a-config-provider :theme="{ token: { colorPrimary: '#1677ff' } }">
    <router-view />
  </a-config-provider>
</template>

<script setup lang="ts">
</script>

<style>
#app {
  width: 100%;
  height: 100vh;
}
</style>
