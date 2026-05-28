<template>
    <div class="chat-page">
        <aside class="chat-sidebar" :class="{ collapsed: !sidebarVisible }">
            <div class="sidebar-header">
                <div class="sidebar-title-row">
                    <div class="sidebar-title">HKG-RAG</div>
                    <button class="sidebar-toggle" @click="toggleSidebar" aria-label="切换侧栏">
                        <LeftBar v-if="sidebarVisible" theme="outline" size="22" fill="#333" />
                    </button>
                </div>
            </div>

            <div v-if="sidebarVisible" class="sidebar-body">
                <div class="sidebar-menu">
                    <div class="menu-item" @click="createNewDialog">
                        <EditTwo theme="outline" size="20" fill="#333" />
                        <span>创建聊天</span>
                    </div>

                    <div class="menu-item">
                        <Search theme="outline" size="20" fill="#333" />
                        <span>搜索聊天</span>
                    </div>

                    <div class="menu-item">
                        <AllApplication theme="outline" size="20" fill="#333" />
                        <span>更多</span>
                    </div>
                </div>

                <div class="history-section">
                    <div class="history-title">历史对话</div>
                    <ul class="history-list">
                        <li v-for="(dialog, index) in dialogs" :key="dialog.id"
                            :class="{ active: activeIndex === index }" @click="selectDialog(index)">
                            {{ dialog.title }}
                        </li>
                    </ul>
                </div>
            </div>

            <div v-else class="sidebar-collapsed" @click="sidebarVisible = true" aria-label="展开侧栏">
                <RightBar theme="outline" size="22" fill="#333" />
            </div>
        </aside>

        <main class="chat-main">
            <section class="chat-content">
                <div class="message-list" ref="messageList">
                    <div v-for="(msg, index) in currentDialog.messages" :key="index" :class="['message-row', msg.role]">
                        <div class="message-inner">
                            <div class="bubble" :class="msg.role">
                                <!-- 思考中动态效果判断 -->
                                <span v-if="msg.content === '思考中...'" class="loading-dots">思考中</span>
                                <span v-else>{{ msg.content }}</span>
                            </div>
                        </div>
                    </div>
                </div>
            </section>

            <section class="chat-input-area">
                <div class="textarea-wrapper">
                    <textarea id="userInput" name="userInput" v-model="userInput"
                        @keyup.enter.exact.prevent="sendMessage" placeholder="请输入你的问题..." rows="2"></textarea>
                    <button class="send-button" @click="sendMessage">发送</button>
                </div>
            </section>

            <footer class="chat-footer">
                <span>内容由AI生成，仅供参考</span>
            </footer>
        </main>
    </div>
</template>

<script setup>
import { ref, reactive, computed, watch, nextTick } from 'vue'
import { LeftBar, RightBar, EditTwo, Search, AllApplication } from '@icon-park/vue-next';

import request from '@/utils/request.js'

const sidebarVisible = ref(true)
const userInput = ref('')
const activeIndex = ref(0)
const dialogs = ref([
    {
        id: Date.now(),
        title: '默认对话',
        messages: [{ role: 'assistant', content: '你好，我是AI，有什么可以帮你？' }],
    },
])

const currentDialog = computed(() => dialogs.value[activeIndex.value])
const messageList = ref(null)

function toggleSidebar() {
    sidebarVisible.value = !sidebarVisible.value
}

function createNewDialog() {
    const newDialog = {
        id: Date.now(),
        title: `会话 ${dialogs.value.length + 1}`,
        messages: [{ role: 'assistant', content: '新的会话已创建，请开始提问。' }],
    }
    dialogs.value.unshift(newDialog)
    activeIndex.value = 0
    scrollToBottom()
}

function selectDialog(index) {
    activeIndex.value = index
    scrollToBottom()
}

function scrollToBottom() {
    nextTick(() => {
        const el = messageList.value
        if (el) {
            el.scrollTop = el.scrollHeight
        }
    })
}

async function sendMessage() {
    if (!userInput.value.trim()) return
    const inputContent = userInput.value.trim()
    const dialog = currentDialog.value

    // 推入用户消息
    dialog.messages.push({ role: 'user', content: inputContent })

    // 显示思考中
    const assistantMsg = reactive({ role: 'assistant', content: '思考中...' })
    dialog.messages.push(assistantMsg)

    userInput.value = ''
    scrollToBottom()

    try {
        const response = await fetch(`/api/chat?message=${encodeURIComponent(inputContent)}&sessionId=${dialog.id}`, {
            method: 'POST',
        })

        const res = await response.json()

        // 关键：直接替换掉思考中，不留痕迹
        dialog.messages.pop()
        const realMsg = reactive({ role: 'assistant', content: '' })
        dialog.messages.push(realMsg)

        let text = res.code === 200 ? res.data : '请求失败：' + res.message
        let index = 0

        function type() {
            if (index < text.length) {
                realMsg.content += text.charAt(index)
                index++
                scrollToBottom()
                setTimeout(type, 15)
            }
        }
        type()

    } catch (err) {
        console.error('请求错误', err)
        assistantMsg.content = '网络异常，请稍后重试'
    }
}

watch(activeIndex, scrollToBottom)
</script>

<style scoped>
/* 全局盒模型，防止宽度溢出 */
* {
    box-sizing: border-box;
}

html,
body {
    margin: 0;
    padding: 0;
    overflow-x: hidden;
}

.chat-page {
    display: flex;
    min-height: 100vh;
    height: 100vh;
    background: #f4f7fb;
    color: #333;
    overflow: hidden;
}

.chat-sidebar {
    width: 280px;
    min-width: 280px;
    background: #f9fafb;
    border-right: 1px solid #e6ebf2;
    display: flex;
    flex-direction: column;
    position: sticky;
    top: 0;
    height: 100vh;
    overflow: hidden;
}

.chat-sidebar.collapsed {
    width: 72px;
    min-width: 72px;
}

.sidebar-header {
    padding: 20px 18px;
    border-bottom: 1px solid #e6ebf2;
    display: flex;
    flex-direction: column;
    gap: 10px;
}

.sidebar-title-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    width: 100%;
}

.sidebar-title {
    font-size: 15px;
    font-weight: 600;
}

.new-dialog-container {
    margin: 10px;
}

.sidebar-menu {
    padding: 8px 18px;
}

.menu-item {
    display: flex;
    align-items: center;
    justify-content: flex-start;
    gap: 8px;
    padding: 10px 12px;
    border-radius: 8px;
    cursor: pointer;
    font-size: 14px;
    color: #333;
    transition: background 0.2s ease;
    line-height: 1;
}

.menu-item .i-icon {
    display: flex !important;
    align-items: center !important;
    justify-content: center !important;
    height: 20px !important;
}

.menu-item:hover {
    background: #e5e7eb;
}

.new-dialog {
    width: 100%;
    border: 1px solid #d1d5db;
    border-radius: 8px;
    padding: 10px 14px;
    cursor: pointer;
    background: #ffffff;
    color: #333;
    font-size: 18px;
    transition: background 0.2s ease, border-color 0.2s ease;
}

.sidebar-toggle {
    width: 32px;
    height: 32px;
    padding: 0;
    background: transparent;
    border: none;
    border-radius: 6px;
    color: #409eff;
    cursor: pointer;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    transition: background 0.2s ease;
}

.sidebar-toggle:hover,
.new-dialog:hover {
    background: #f0f4ff;
    border-color: #b8d6ff;
}

.sidebar-body {
    flex: 1;
    display: flex;
    width: 100%;
    flex-direction: column;
    overflow: hidden;
}

.history-section {
    padding: 0 18px 18px;
    overflow-y: auto;
}

.history-title {
    margin: 20px 0 10px;
    font-size: 14px;
    color: #6b7280;
}

.history-list {
    list-style: none;
    padding: 0;
    margin: 0;
    display: flex;
    flex-direction: column;
    gap: 10px;
}

.history-list li {
    padding: 12px 14px;
    border-radius: 10px;
    background: #f4f7fb;
    cursor: pointer;
    transition: background 0.2s ease;
}

.history-list li.active,
.history-list li:hover {
    background: #e6f2ff;
}

.sidebar-collapsed {
    display: flex;
    align-items: center;
    justify-content: center;
    flex: 1;
    cursor: pointer;
    background: transparent;
    border: none;
    border-radius: 6px;
    font-size: 18px;
    transition: background 0.2s ease;
}

.sidebar-collapsed:hover {
    background: #f0f4ff;
    border-color: #b8d6ff;
}

.chat-main {
    flex: 1;
    display: flex;
    flex-direction: column;
    padding: 24px 24px 10px 24px;
    width: 100%;
    max-width: 100%;
}

.chat-main-header {
    padding: 0px 0 18px;
}

.chat-title {
    font-size: 24px;
    font-weight: 700;
}

.chat-subtitle {
    margin-top: 6px;
    color: #6b7280;
    font-size: 14px;
}

.chat-content {
    flex: 1;
    background: #ffffff;
    border-radius: 20px;
    padding: 24px;
    box-shadow: 0 8px 24px rgba(15, 23, 42, 0.06);
    overflow: hidden;
    display: flex;
    flex-direction: column;
}

.message-list {
    flex: 1;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    gap: 18px;
    padding-right: 10px;
}

.message-row {
    display: flex;
    width: 100%;
}

.message-row.assistant {
    justify-content: flex-start;
}

.message-row.user {
    justify-content: flex-end;
}

.message-inner {
    display: flex;
    align-items: flex-end;
    gap: 10px;
    max-width: 75%;
}

.bubble {
    padding: 14px 16px;
    border-radius: 18px;
    line-height: 1.6;
    white-space: pre-wrap;
    word-break: break-word;
}

.bubble.assistant {
    background: #f7f9ff;
    color: #111827;
    border-top-left-radius: 4px;
}

.bubble.user {
    background: #409eff;
    color: #ffffff;
    border-top-right-radius: 4px;
}

.avatar {
    width: 42px;
    height: 42px;
    border-radius: 50%;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    font-size: 14px;
    font-weight: 700;
    color: #ffffff;
}

.avatar-ai {
    background: #409eff;
}

.avatar-user {
    background: #6b7280;
}

.chat-input-area {
    margin-top: 18px;
    width: 100%;
}

.textarea-wrapper {
    position: relative;
    width: 100%;
}

.chat-input-area textarea {
    width: 100%;
    min-height: 96px;
    padding: 14px 110px 14px 16px;
    border-radius: 16px;
    border: 1px solid #d1d5db;
    resize: none;
    font-size: 15px;
    line-height: 1.6;
    background: #ffffff;
    outline: none;
}

.send-button {
    position: absolute;
    right: 12px;
    bottom: 12px;
    width: 90px;
    padding: 8px 10px;
    border: none;
    border-radius: 12px;
    background: #409eff;
    color: #ffffff;
    cursor: pointer;
    font-size: 14px;
}

.send-button:hover {
    background: #2563eb;
}

.chat-footer {
    margin-top: 10px;
    color: #6b7280;
    font-size: 13px;
    text-align: center;
}

/* 思考中动态点点动画 */
.loading-dots::after {
    content: '';
    animation: dots 2s infinite step-start;
}

@keyframes dots {
    0% {
        content: '';
    }

    25% {
        content: '.';
    }

    50% {
        content: '..';
    }

    75% {
        content: '...';
    }

    100% {
        content: '';
    }
}

@media screen and (max-width: 960px) {
    .chat-page {
        flex-direction: column;
        overflow-y: auto;
    }

    .chat-sidebar,
    .chat-sidebar.collapsed {
        width: 100%;
        min-width: 100%;
        height: auto;
        position: relative;
    }

    .chat-main {
        padding: 16px;
    }
}
</style>