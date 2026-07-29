import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('token') || '')
  const username = ref(localStorage.getItem('username') || '')
  const nickname = ref(localStorage.getItem('nickname') || '')
  const cash = ref(Number(localStorage.getItem('cash') || '0'))

  const setToken = (newToken: string, name: string, nick: string, userCash?: number) => {
    token.value = newToken
    username.value = name
    nickname.value = nick
    if (userCash !== undefined) {
      cash.value = userCash
      localStorage.setItem('cash', String(userCash))
    }
    localStorage.setItem('token', newToken)
    localStorage.setItem('username', name)
    localStorage.setItem('nickname', nick)
  }

  const setCash = (amount: number) => {
    cash.value = amount
    localStorage.setItem('cash', String(amount))
  }

  const clearToken = () => {
    token.value = ''
    username.value = ''
    nickname.value = ''
    cash.value = 0
    localStorage.removeItem('token')
    localStorage.removeItem('username')
    localStorage.removeItem('nickname')
    localStorage.removeItem('cash')
  }

  return { token, username, nickname, cash, setToken, setCash, clearToken }
})
