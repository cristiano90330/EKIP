/* 为 MyBatis Plus 注册 PostgreSQL 分页拦截器。 */

package com.pony.knowledge.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配 MyBatis-Plus 分页拦截器。显式使用 PostgreSQL 方言，使分页查询按当前数据库
 * 生成 LIMIT/OFFSET；Mapper 仍负责声明查询，本配置不参与用户授权或业务决策。
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 装配面向 PostgreSQL 的 MyBatis Plus 分页处理。
     * 上游为 Spring 容器组件装配。
     *
     * @return 本次计算或查询得到的 MybatisPlusInterceptor 结果
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(){

        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));

        return interceptor;
    }
}
