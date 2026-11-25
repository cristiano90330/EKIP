/* 在应用启动时将配置的部署默认模型登记到模型注册表。 */

package com.pony.knowledge.modelmanagement;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Registers the immutable shared model group after Flyway has prepared the database. */
@Component
public class DeploymentModelInitializer implements ApplicationRunner {
    private final ModelRegistryService models;

    /**
     * 保存在应用启动时将配置的部署默认模型登记到模型注册表所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param models ModelRegistryService，负责管理模型档案、用途绑定、凭证与执行配置的解析
     */
    public DeploymentModelInitializer(ModelRegistryService models) {
        this.models = models;
    }

    /**
     * 在后端启动后同步部署默认模型到注册表。
     * 下游为 ModelRegistryService.initializeDeploymentDefaults。
     *
     * @param args 启动进程时传入的命令行参数
     */
    @Override
    public void run(ApplicationArguments args) {
        models.initializeDeploymentDefaults();
    }
}
