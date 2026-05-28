import axios from 'axios'

const request = axios.create({
    baseURL: import.meta.env.VITE_API_BASE || 'http://localhost:8080',
    timeout: 600000, // 10 分钟
})

// // 请求拦截、响应拦截 …
// request.interceptors.request.use(config => {
//     const token = localStorage.getItem('token')
//     if (token) config.headers.Authorization = `Bearer ${token}`
//     return config
// })

//返回成功把后端返回的 最外层包装 剥掉；返回失败HTTP 状态码 ≥400 或网络异常就会走到这里
request.interceptors.response.use(res => res.data, err => {
    console.error(err)
    return Promise.reject(err)
})

export default request