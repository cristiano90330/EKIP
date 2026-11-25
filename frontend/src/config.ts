/** 从前端构建环境读取模拟模式开关，供页面和 API 模块选择数据源。 */

/**
 * 是否启用 mock 数据层（仅开发调试用）。
 * 启动方式：`VITE_USE_MOCK=true npm run dev`；后端接口就绪后去掉该变量即全走真实后端。
 */
export const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'
