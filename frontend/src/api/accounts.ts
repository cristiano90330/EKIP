/** 对接管理员账号与部门接口，提供分页、启停和部门维护能力。 */

import { http } from './http'

export interface ManagedAccount {
  id: number
  username: string
  enabled: boolean
  currentAdmin: boolean
  department: string | null
}

export interface Department { id: number; name: string }

export interface AccountPage {
  accounts: ManagedAccount[]
  nextAfterId: number | null
}

const accountAdminPath = '/user/account-admin'

/**
 * 按账号游标读取管理员可见的账号页。
 * 接收后续页游标，返回 AccountPage。
 * 由 AccountAdminPanel.vue 的 load 触发。
 * 下游调用 AccountAdminController／DepartmentAdminController。
 */
export const listAccounts = (afterId = 0): Promise<AccountPage> =>
  http.get<AccountPage, AccountPage>(accountAdminPath, { params: { afterId } })

/**
 * 修改指定账号的可用状态并返回服务器确认的资料。
 * 接收记录 ID、enabled。
 * 由 AccountAdminPanel.vue 的 changeStatus 触发。
 * 下游调用 AccountAdminController／DepartmentAdminController。
 */
export const updateAccountStatus = (id: number, enabled: boolean): Promise<ManagedAccount> =>
  http.put<ManagedAccount, ManagedAccount>(accountAdminPath + '/' + id + '/status', { enabled })

const departmentsPath = accountAdminPath + '/departments'

/**
 * 读取当前可分配的部门列表。
 * 返回 Department[]。
 * 由 AccountAdminPanel.vue 的 loadDepartments 触发。
 * 下游调用 AccountAdminController／DepartmentAdminController。
 */
export const listDepartments = (): Promise<Department[]> => http.get<Department[], Department[]>(departmentsPath)

/**
 * 按名称创建部门并返回已登记记录。
 * 接收名称。
 * 由 AccountAdminPanel.vue 的 saveDepartment 触发。
 * 下游调用 AccountAdminController／DepartmentAdminController。
 */
export const createDepartment = (name: string): Promise<Department> =>
  http.post<Department, Department>(departmentsPath, { name })

/**
 * 提交部门的最新名称并返回更新记录。
 * 接收部门 ID、名称。
 * 由 AccountAdminPanel.vue 的 saveDepartment 触发。
 * 下游调用 AccountAdminController／DepartmentAdminController。
 */
export const renameDepartment = (id: number, name: string): Promise<Department> =>
  http.put<Department, Department>(departmentsPath + '/' + id, { name })

/**
 * 删除指定部门并返回维护结果。
 * 接收部门 ID。
 * 由 AccountAdminPanel.vue 的 removeDepartment 触发。
 * 下游调用 AccountAdminController／DepartmentAdminController。
 */
export const deleteDepartment = (id: number): Promise<string> => http.delete<string, string>(departmentsPath + '/' + id)

/**
 * 给指定账号分配或清空部门，并返回变更后的账号信息。
 * 接收账号 ID、部门 ID。
 * 由 AccountAdminPanel.vue 的 assign 触发。
 * 下游调用 AccountAdminController／DepartmentAdminController。
 */
export const assignDepartment = (userId: number, departmentId: number | null): Promise<ManagedAccount> =>
  http.put<ManagedAccount, ManagedAccount>(accountAdminPath + '/' + userId + '/department', { departmentId })
