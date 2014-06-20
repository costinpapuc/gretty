/*
 * gretty
 *
 * Copyright 2013  Andrey Hihlovskiy.
 *
 * See the file "license.txt" for copying and usage permission.
 */
package org.akhikhl.gretty

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 *
 * @author akhikhl
 */
class GrettyPlugin implements Plugin<Project> {

  protected static final Logger log = LoggerFactory.getLogger(GrettyPlugin)

  private void addConfigurations(Project project) {
    project.configurations {
      gretty
      springBoot {
        if(project.configurations.findByName('runtime'))
          extendsFrom project.configurations.runtime
        exclude module: 'spring-boot-starter-tomcat'
        exclude group: 'org.eclipse.jetty'
      }
      grettyNoSpringBoot {
        extendsFrom project.configurations.gretty
        exclude group: 'org.springframework.boot'
      }
      grettyRunnerSpringBoot
      grettyRunnerSpringBootJetty {
        extendsFrom project.configurations.grettyRunnerSpringBoot
      }
      grettyRunnerSpringBootTomcat {
        extendsFrom project.configurations.grettyRunnerSpringBoot
      }
      grettySpringLoaded {
        transitive = false
      }
    }
    if(!project.configurations.findByName('providedCompile'))
      project.configurations {
        providedCompile
        compile.extendsFrom(providedCompile)
      }
    ServletContainerConfig.getConfigs().each { configName, config ->
      project.configurations.create config.servletContainerRunnerConfig
    }
    SpringBootResolutionStrategy.apply(project)
  }

  private void addDependencies(Project project) {

    String grettyVersion = Externalized.getString('grettyVersion')

    project.dependencies {
      grettyRunnerSpringBoot "org.akhikhl.gretty:gretty-runner-spring-boot:$grettyVersion"
      grettyRunnerSpringBootJetty "org.akhikhl.gretty:gretty-runner-spring-boot-jetty:$grettyVersion", {
        // concrete implementation is chosen depending on servletContainer property
        exclude group: 'org.akhikhl.gretty', module: 'gretty-runner-jetty9'
      }
      grettyRunnerSpringBootTomcat "org.akhikhl.gretty:gretty-runner-spring-boot-tomcat:$grettyVersion", {
        // concrete implementation is chosen depending on servletContainer property
        exclude group: 'org.apache.tomcat.embed'
        exclude group: 'javax.servlet', module: 'javax.servlet-api'
      }
      grettySpringLoaded 'org.springframework:springloaded:1.2.0.RELEASE'
    }

    ServletContainerConfig.getConfig(project.gretty.servletContainer).with { config ->
      def closure = config.servletApiDependencies
      closure = closure.rehydrate(config, closure.owner, closure.thisObject)
      closure.resolveStrategy = Closure.DELEGATE_FIRST
      closure(project)
    }

    ServletContainerConfig.getConfigs().each { configName, config ->
      def closure = config.servletContainerRunnerDependencies
      closure = closure.rehydrate(config, closure.owner, closure.thisObject)
      closure.resolveStrategy = Closure.DELEGATE_FIRST
      closure(project)
    }

    if(project.gretty.springBoot)
      project.dependencies {
        compile 'org.springframework.boot:spring-boot-starter-web'
        springBoot 'org.springframework.boot:spring-boot-starter-jetty'
      }

    for(String overlay in project.gretty.overlays)
      project.dependencies.add 'providedCompile', project.project(overlay)
  }

  private void addExtensions(Project project) {

    project.extensions.create('gretty', GrettyExtension)

    project.extensions.create('farm', Farm)
    project.farm.integrationTestTask = 'integrationTest'

    project.extensions.create('farms', Farms)
    project.farms.farmsMap[''] = project.farm
  }

  private void addRepositories(Project project) {
    project.repositories {
      mavenLocal()
      jcenter()
      mavenCentral()
      maven { url 'http://repo.spring.io/release' }
      maven { url 'http://repo.spring.io/milestone' }
      maven { url 'http://repo.spring.io/snapshot' }
    }
  }

  private void addTaskDependencies(Project project) {

    project.tasks.whenObjectAdded { task ->
      if(task instanceof JettyStartTask)
        task.dependsOn {
          task.effectiveInplace ? project.tasks.prepareInplaceWebApp : project.tasks.prepareArchiveWebApp
        }
      else if(task instanceof FarmStartTask)
        task.dependsOn {
          task.getWebAppConfigsForProjects().collect {
            def proj = project.project(it.projectPath)
            boolean inplace = it.inplace == null ? task.inplace : it.inplace
            inplace ? proj.tasks.prepareInplaceWebApp : proj.tasks.prepareArchiveWebApp
          }
        }
    }
  }

  private void addTasks(Project project) {

    project.task('prepareInplaceWebAppFolder', group: 'gretty') {
      description = 'Copies webAppDir of this web-app and all overlays (if any) to ${buildDir}/inplaceWebapp'
      inputs.dir ProjectUtils.getWebAppDir(project)
      outputs.dir "${project.buildDir}/inplaceWebapp"
      doLast {
        ProjectUtils.prepareInplaceWebAppFolder(project)
      }
    }

    project.task('prepareInplaceWebAppClasses', group: 'gretty') {
      description = 'Compiles classes of this web-app and all overlays (if any)'
      dependsOn project.tasks.classes
      for(String overlay in project.gretty.overlays)
        dependsOn "$overlay:prepareInplaceWebAppClasses"
    }

    project.task('prepareInplaceWebApp', group: 'gretty') {
      description = 'Prepares inplace web-app'
      dependsOn project.tasks.prepareInplaceWebAppFolder
      dependsOn project.tasks.prepareInplaceWebAppClasses
    }

    def archiveTask = project.tasks.findByName('war') ?: project.tasks.jar

    if(project.gretty.overlays) {

      project.ext.finalArchivePath = archiveTask.archivePath

      archiveTask.archiveName = 'partial.' + (project.tasks.findByName('war') ? 'war' : 'jar')

      // 'explodeWebApps' task is only activated by 'overlayArchive' task
      project.task('explodeWebApps', group: 'gretty') {
        description = 'Explodes this web-app and all overlays (if any) to ${buildDir}/explodedWebapp'
        for(String overlay in project.gretty.overlays)
          dependsOn "$overlay:assemble" as String
        dependsOn archiveTask
        for(String overlay in project.gretty.overlays)
          inputs.file { ProjectUtils.getFinalArchivePath(project.project(overlay)) }
        inputs.file archiveTask.archivePath
        outputs.dir "${project.buildDir}/explodedWebapp"
        doLast {
          ProjectUtils.prepareExplodedWebAppFolder(project)
        }
      }

      project.task('overlayArchive', group: 'gretty') {
        description = 'Creates archive from exploded web-app in ${buildDir}/explodedWebapp'
        dependsOn project.tasks.explodeWebApps
        inputs.dir "${project.buildDir}/explodedWebapp"
        outputs.file project.ext.finalArchivePath
        doLast {
          ant.zip destfile: project.ext.finalArchivePath, basedir: "${project.buildDir}/explodedWebapp"
        }
      }

      project.tasks.assemble.dependsOn project.tasks.overlayArchive
    } // overlays

    project.task('prepareArchiveWebApp', group: 'gretty') {
      description = 'Prepares war web-app'
      if(project.gretty.overlays)
        dependsOn project.tasks.overlayArchive
      else
        dependsOn archiveTask
    }

    project.task('jettyRun', type: JettyStartTask, group: 'gretty') {
      description = 'Starts web-app inplace, in interactive mode.'
    }

    // As soon as farm plugin applies to the same project, it takes over run task.
    if(!project.ext.has('grettyFarmPluginName'))
      project.tasks.run.dependsOn 'jettyRun'

    project.task('jettyRunDebug', type: JettyStartTask, group: 'gretty') {
      description = 'Starts web-app inplace, in debug and interactive mode.'
      debug = true
    }

    // As soon as farm plugin applies to the same project, it takes over debug task.
    if(!project.ext.has('grettyFarmPluginName'))
      project.tasks.debug.dependsOn 'jettyRunDebug'

    project.task('jettyStart', type: JettyStartTask, group: 'gretty') {
      description = 'Starts web-app inplace (stopped by \'jettyStop\').'
      interactive = false
    }

    project.task('jettyStartDebug', type: JettyStartTask, group: 'gretty') {
      description = 'Starts web-app inplace, in debug mode (stopped by \'jettyStop\').'
      interactive = false
      debug = true
    }

    if(project.plugins.findPlugin(org.gradle.api.plugins.WarPlugin)) {
      project.task('jettyRunWar', type: JettyStartTask, group: 'gretty') {
        description = 'Starts web-app on WAR-file, in interactive mode.'
        inplace = false
      }

      project.task('jettyRunWarDebug', type: JettyStartTask, group: 'gretty') {
        description = 'Starts web-app on WAR-file, in debug and interactive mode.'
        inplace = false
        debug = true
      }

      project.task('jettyStartWar', type: JettyStartTask, group: 'gretty') {
        description = 'Starts web-app on WAR-file (stopped by \'jettyStop\').'
        inplace = false
        interactive = false
      }

      project.task('jettyStartWarDebug', type: JettyStartTask, group: 'gretty') {
        description = 'Starts web-app on WAR-file, in debug mode (stopped by \'jettyStop\').'
        inplace = false
        interactive = false
        debug = true
      }
    }

    project.task('jettyStop', type: JettyStopTask, group: 'gretty') {
      description = 'Sends \'stop\' command to running jetty server.'
    }

    project.task('jettyRestart', type: JettyRestartTask, group: 'gretty') {
      description = 'Sends \'restart\' command to running jetty server.'
    }

    project.task('jettyBeforeIntegrationTest', type: JettyBeforeIntegrationTestTask, group: 'gretty') {
      description = 'Starts jetty server before integration test.'
    }

    project.task('jettyAfterIntegrationTest', type: JettyAfterIntegrationTestTask, group: 'gretty') {
      description = 'Stops jetty server after integration test.'
    }

    project.farms.farmsMap.each { fname, farm ->

      String farmDescr = fname ? "farm '${fname}'" : 'default farm'

      project.task('farmRun' + fname, type: FarmStartTask, group: 'gretty') {
        description = "Starts ${farmDescr} inplace, in interactive mode."
        farmName = fname
        if(!fname)
          doFirst {
            GradleUtils.disableTaskOnOtherProjects(project, 'run')
            GradleUtils.disableTaskOnOtherProjects(project, 'jettyRun')
            GradleUtils.disableTaskOnOtherProjects(project, 'farmRun')
          }
      }

      project.task('farmRunDebug' + fname, type: FarmStartTask, group: 'gretty') {
        description = "Starts ${farmDescr} inplace, in debug and in interactive mode."
        farmName = fname
        debug = true
        if(!fname)
          doFirst {
            GradleUtils.disableTaskOnOtherProjects(project, 'debug')
            GradleUtils.disableTaskOnOtherProjects(project, 'jettyRunDebug')
            GradleUtils.disableTaskOnOtherProjects(project, 'farmRunDebug')
          }
      }

      project.task('farmStart' + fname, type: FarmStartTask, group: 'gretty') {
        description = "Starts ${farmDescr} inplace (stopped by 'farmStop${fname}')."
        farmName = fname
        interactive = false
      }

      project.task('farmStartDebug' + fname, type: FarmStartTask, group: 'gretty') {
        description = "Starts ${farmDescr} inplace, in debug mode (stopped by 'farmStop${fname}')."
        farmName = fname
        interactive = false
        debug = true
      }

      project.task('farmRunWar' + fname, type: FarmStartTask, group: 'gretty') {
        description = "Starts ${farmDescr} on WAR-files, in interactive mode."
        farmName = fname
        inplace = false
      }

      project.task('farmRunWarDebug' + fname, type: FarmStartTask, group: 'gretty') {
        description = "Starts ${farmDescr} on WAR-files, in debug and in interactive mode."
        farmName = fname
        debug = true
        inplace = false
      }

      project.task('farmStartWar' + fname, type: FarmStartTask, group: 'gretty') {
        description = "Starts ${farmDescr} on WAR-files (stopped by 'farmStop${fname}')."
        farmName = fname
        interactive = false
        inplace = false
      }

      project.task('farmStartWarDebug' + fname, type: FarmStartTask, group: 'gretty') {
        description = "Starts ${farmDescr} on WAR-files, in debug (stopped by 'farmStop${fname}')."
        farmName = fname
        interactive = false
        debug = true
        inplace = false
      }

      project.task('farmStop' + fname, type: FarmStopTask, group: 'gretty') {
        description = "Sends \'stop\' command to a running ${farmDescr}."
        farmName = fname
      }

      project.task('farmRestart' + fname, type: FarmRestartTask, group: 'gretty') {
        description = "Sends \'restart\' command to a running ${farmDescr}."
        farmName = fname
      }

      project.task('farmBeforeIntegrationTest' + fname, type: FarmBeforeIntegrationTestTask, group: 'gretty') {
        description = "Starts ${farmDescr} before integration test."
        farmName = fname
      }

      project.task('farmIntegrationTest' + fname, type: FarmIntegrationTestTask, group: 'gretty') {
        description = "Runs integration tests on ${farmDescr} web-apps."
        farmName = fname
        dependsOn 'farmBeforeIntegrationTest' + fname
        finalizedBy 'farmAfterIntegrationTest' + fname
      }

      project.task('farmAfterIntegrationTest' + fname, type: FarmAfterIntegrationTestTask, group: 'gretty') {
        description = "Stops ${farmDescr} after integration test."
        farmName = fname
      }
    } // farmsMap
  } // addTasks

  private void afterAfterEvaluate(Project project) {

    for(Closure afterEvaluateClosure in project.gretty.afterEvaluate) {
      afterEvaluateClosure.delegate = project.gretty
      afterEvaluateClosure.resolveStrategy = Closure.DELEGATE_FIRST
      afterEvaluateClosure()
    }

    if(!project.tasks.jettyBeforeIntegrationTest.integrationTestTaskAssigned)
      project.tasks.jettyBeforeIntegrationTest.integrationTestTask null // default binding

    if(!project.tasks.jettyAfterIntegrationTest.integrationTestTaskAssigned)
      project.tasks.jettyAfterIntegrationTest.integrationTestTask null // default binding

    project.farms.farmsMap.each { fname, farm ->

      for(Closure afterEvaluateClosure in farm.afterEvaluate) {
        afterEvaluateClosure.delegate = farm
        afterEvaluateClosure.resolveStrategy = Closure.DELEGATE_FIRST
        afterEvaluateClosure()
      }

      if(!project.tasks.farmBeforeIntegrationTest.integrationTestTaskAssigned)
        project.tasks.farmBeforeIntegrationTest.integrationTestTask null // default binding

      if(!project.tasks.farmIntegrationTest.integrationTestTaskAssigned)
        project.tasks.farmIntegrationTest.integrationTestTask null // default binding

      if(!project.tasks.farmAfterIntegrationTest.integrationTestTaskAssigned)
        project.tasks.farmAfterIntegrationTest.integrationTestTask null // default binding
    }
  }

  void apply(final Project project) {

    addExtensions(project)
    addConfigurations(project)

    if(!project.tasks.findByName('run'))
      project.task('run')

    if(!project.tasks.findByName('debug'))
      project.task('debug')

    addTaskDependencies(project)

    project.afterEvaluate {

      addRepositories(project)
      addDependencies(project)
      addTasks(project)
      afterAfterEvaluate(project)

    } // afterEvaluate
  } // apply
}