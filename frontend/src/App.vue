<script setup lang="ts">
import AuthView from './auth/AuthView.vue'
import DevelopmentCheck from './DevelopmentCheck.vue'

const showDevelopmentCheck = window.location.pathname === '/dev'
</script>

<template>
  <DevelopmentCheck v-if="showDevelopmentCheck" />
  <AuthView v-else />
</template>
