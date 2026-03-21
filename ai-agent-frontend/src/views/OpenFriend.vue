<template>
  <div class="open-friend-container">
    <div class="header">
      <div class="back-button" @click="goBack">返回</div>
      <h1 class="title">OpenFriend</h1>
      <div class="chat-id">会话ID: {{ chatId }}</div>
    </div>

    <div class="mode-selector">
      <button 
        v-for="m in modes" 
        :key="m.value"
        :class="['mode-btn', { active: currentMode === m.value }]"
        @click="currentMode = m.value"
      >
        {{ m.label }}
      </button>
    </div>
    
    <div class="content-wrapper">
      <div class="chat-area">
        <ChatRoom 
          :messages="messages" 
          :connection-status="connectionStatus"
          ai-type="love"
          @send-message="sendMessage"
        />
      </div>
    </div>
    
    <div class="footer-container">
      <AppFooter />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { useHead } from '@vueuse/head'
import ChatRoom from '../components/ChatRoom.vue'
import AppFooter from '../components/AppFooter.vue'
import { chatWithLoveApp, getChatMessages } from '../api'

// 设置页面标题和元数据
useHead({
  title: 'OpenFriend - AI超级智能体应用平台',
  meta: [
    {
      name: 'description',
      content: 'OpenFriend 是 AI 超级智能体应用平台的专业伙伴，提供多模式对话体验'
    },
    {
      name: 'keywords',
      content: 'OpenFriend,智能伙伴,情感顾问,AI聊天,超级智能体'
    }
  ]
})

const router = useRouter()
const messages = ref([])
const chatId = ref('')
const connectionStatus = ref('disconnected')
let eventSource = null

// 模式定义
const currentMode = ref('normal')
const modes = [
  { label: '普通模式', value: 'normal' },
  { label: '深度思考', value: 'thinking' },
  { label: '超级智能体', value: 'super' }
]

// 固定会话ID
const FIXED_CHAT_ID = 'open_friend_default'

// 添加消息到列表
const addMessage = (content, isUser, type = '', time) => {
  messages.value.push({
    content,
    isUser,
    type,
    time: time || new Date().getTime()
  })
}

// 发送消息
const sendMessage = (message) => {
  addMessage(message, true)
  
  if (eventSource) {
    eventSource.close()
  }
  
  // 所有模式统一处理：创建一个 AI 消息气泡，流式追加内容
  const aiMessageIndex = messages.value.length
  addMessage('', false)
  
  connectionStatus.value = 'connecting'
  eventSource = chatWithLoveApp(message, chatId.value, currentMode.value)
  
  eventSource.onmessage = (event) => {
    const data = event.data
    
    if (data && data !== '[DONE]') {
      if (aiMessageIndex < messages.value.length) {
        messages.value[aiMessageIndex].content += data
      }
    }
    
    if (data === '[DONE]') {
      connectionStatus.value = 'disconnected'
      eventSource.close()
    }
  }
  
  eventSource.onerror = (error) => {
    console.error('SSE Error:', error)
    connectionStatus.value = 'error'
    eventSource.close()
  }
}

// 返回主页
const goBack = () => {
  router.push('/')
}

// 加载历史消息
const loadHistory = async () => {
  chatId.value = FIXED_CHAT_ID
  try {
    const { data } = await getChatMessages(FIXED_CHAT_ID)
    if (data && data.length > 0) {
      messages.value = data.map(m => ({
        content: m.content,
        isUser: m.role === 'USER',
        time: m.createdAt ? new Date(m.createdAt).getTime() : Date.now()
      }))
      return
    }
  } catch (e) {
    console.warn('加载历史消息失败', e)
  }
  addMessage('你好！我是 OpenFriend。我已经准备好为你提供帮助了。你可以选择普通模式、深度思考模式或超级智能体模式与我交流。', false)
}

onMounted(() => {
  loadHistory()
})

onBeforeUnmount(() => {
  if (eventSource) {
    eventSource.close()
  }
})
</script>

<style scoped>
.open-friend-container {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background-color: #f5f7f9;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 24px;
  background-color: #4a90e2;
  color: white;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  position: sticky;
  top: 0;
  z-index: 10;
}

.back-button {
  font-size: 16px;
  cursor: pointer;
  display: flex;
  align-items: center;
  transition: opacity 0.2s;
}

.back-button:hover {
  opacity: 0.8;
}

.back-button:before {
  content: '←';
  margin-right: 8px;
}

.title {
  font-size: 20px;
  font-weight: bold;
  margin: 0;
}

.chat-id {
  font-size: 14px;
  opacity: 0.8;
}

.mode-selector {
  display: flex;
  justify-content: center;
  gap: 12px;
  padding: 12px;
  background-color: white;
  border-bottom: 1px solid #eee;
}

.mode-btn {
  padding: 6px 16px;
  border-radius: 20px;
  border: 1px solid #ddd;
  background: white;
  cursor: pointer;
  transition: all 0.3s;
  font-size: 14px;
}

.mode-btn.active {
  background-color: #4a90e2;
  color: white;
  border-color: #4a90e2;
}

.mode-btn:hover:not(.active) {
  background-color: #f0f7ff;
  border-color: #4a90e2;
}

.content-wrapper {
  display: flex;
  flex-direction: column;
  flex: 1;
}

.chat-area {
  flex: 1;
  padding: 16px;
  overflow: hidden;
  position: relative;
  min-height: calc(100vh - 56px - 60px - 180px);
  margin-bottom: 16px;
}

.footer-container {
  margin-top: auto;
}

@media (max-width: 768px) {
  .header {
    padding: 12px 16px;
  }
  .title {
    font-size: 18px;
  }
  .chat-area {
    padding: 12px;
  }
}
</style> 