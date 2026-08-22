package com.campusdash.domain;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构规则守卫。
 *
 * 架构文档写得再漂亮，几个月后也会烂掉——除非把规则写成测试。
 * 这三条规则进 CI 后，任何人想在领域层 import 一个 Spring 注解，构建直接红。
 */
@AnalyzeClasses(packages = "com.campusdash.domain",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRuleTest {

    /** 规则一：领域层不许碰任何技术框架 */
    @ArchTest
    static final ArchRule domain_should_be_framework_free = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "com.baomidou..",
                    "org.redisson..",
                    "org.apache.rocketmq..",
                    "org.mybatis..",
                    "javax.sql..",
                    "java.sql..")
            .because("领域层必须保持纯 JDK，换掉任何中间件都不该影响业务规则");

    /** 规则二：仓储与端口接口必须定义在领域层的 ports 包里（依赖倒置的落点） */
    @ArchTest
    static final ArchRule repository_ports_live_in_domain_ports = classes()
            .that().haveSimpleNameEndingWith("Repository")
            .or().haveSimpleNameEndingWith("Port")
            .and().areInterfaces()
            .should().resideInAPackage("..domain..ports..")
            .because("端口由领域层定义、基础设施层实现，方向不能反");

    /** 规则三：领域模型不许依赖 ports（聚合不该知道自己怎么被持久化） */
    @ArchTest
    static final ArchRule model_should_not_depend_on_ports = noClasses()
            .that().resideInAPackage("..domain..model..")
            .should().dependOnClassesThat().resideInAPackage("..domain..ports..")
            .because("聚合只表达业务规则，持久化是外部关注点");
}
