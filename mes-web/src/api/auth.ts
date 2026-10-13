import { http } from './http'

/**
 * 认证接口：经 Gateway 的 /api/auth/** 路由（StripPrefix=1）转发到 auth-service 的 POST /auth/login。
 * 成功响应为 ApiResponse 包装 {code,message,data}，data 为 TokenResponse。
 */
export interface LoginRequest {
  username: string
  password: string
}

export interface TokenResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
  username: string
  role: string
  permissions: string[]
}

interface ApiResponse<T> {
  code: string
  message: string
  data: T
}

/** 用户名密码登录，返回令牌及用户快照。 */
export async function login(req: LoginRequest): Promise<TokenResponse> {
  const { data } = await http.post<ApiResponse<TokenResponse>>('/auth/login', req)
  return data.data
}
