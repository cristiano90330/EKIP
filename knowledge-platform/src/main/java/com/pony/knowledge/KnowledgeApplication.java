/* 提供 Spring Boot 后端进程的启动入口。 */

package com.pony.knowledge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Java 业务后端的进程入口，由 Spring Boot 从本包向下扫描组件，并按配置装配 Web、数据源和 MyBatis。
 * main 只启动框架流程，业务初始化检查在 Bean 创建时执行，启动任务恢复监听 ApplicationReadyEvent，
 * 定时探测与恢复交给调度器，正常关闭交给容器生命周期和销毁回调。Python AI 服务独立启动，
 * Java 到达就绪事件阶段不代表 AI 能力或后台文档任务已经就绪。
 */
@SpringBootApplication
public class KnowledgeApplication {

	/**
	 * 以命令行参数启动 Spring Boot 应用。
	 * 上游为 后端进程启动器。
	 * 下游为 SpringApplication.run。
	 *
	 * @param args 启动进程时传入的命令行参数
	 */
	public static void main(String[] args) {
		SpringApplication.run(KnowledgeApplication.class, args);
	}

}
