/*
 * Copyright 2017 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jbpm.springboot.autoconfigure;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.persistence.Embeddable;
import javax.persistence.Entity;
import javax.persistence.EntityManagerFactory;
import javax.persistence.MappedSuperclass;
import javax.persistence.PersistenceException;
import javax.sql.DataSource;
import javax.sql.XADataSource;

import org.dashbuilder.dataprovider.sql.SQLDataSetProvider;
import org.dashbuilder.dataprovider.sql.SQLDataSourceLocator;
import org.dashbuilder.dataset.def.SQLDataSetDef;
import org.drools.core.impl.EnvironmentFactory;
import org.drools.persistence.api.TransactionManager;
import org.jbpm.casemgmt.api.CaseRuntimeDataService;
import org.jbpm.casemgmt.api.CaseService;
import org.jbpm.casemgmt.api.admin.CaseInstanceMigrationService;
import org.jbpm.casemgmt.api.event.CaseEventListener;
import org.jbpm.casemgmt.api.generator.CaseIdGenerator;
import org.jbpm.casemgmt.impl.AuthorizationManagerImpl;
import org.jbpm.casemgmt.impl.CaseRuntimeDataServiceImpl;
import org.jbpm.casemgmt.impl.CaseServiceImpl;
import org.jbpm.casemgmt.impl.admin.CaseInstanceMigrationServiceImpl;
import org.jbpm.casemgmt.impl.event.CaseConfigurationDeploymentListener;
import org.jbpm.casemgmt.impl.generator.TableCaseIdGenerator;
import org.jbpm.executor.ExecutorServiceFactory;
import org.jbpm.executor.impl.event.ExecutorEventSupportImpl;
import org.jbpm.kie.services.impl.FormManagerService;
import org.jbpm.kie.services.impl.FormManagerServiceImpl;
import org.jbpm.kie.services.impl.KModuleDeploymentService;
import org.jbpm.kie.services.impl.ProcessServiceImpl;
import org.jbpm.kie.services.impl.RuntimeDataServiceImpl;
import org.jbpm.kie.services.impl.UserTaskServiceImpl;
import org.jbpm.kie.services.impl.admin.ProcessInstanceAdminServiceImpl;
import org.jbpm.kie.services.impl.admin.ProcessInstanceMigrationServiceImpl;
import org.jbpm.kie.services.impl.admin.UserTaskAdminServiceImpl;
import org.jbpm.kie.services.impl.bpmn2.BPMN2DataServiceImpl;
import org.jbpm.kie.services.impl.query.QueryServiceImpl;
import org.jbpm.runtime.manager.impl.jpa.EntityManagerFactoryManager;
import org.jbpm.services.api.DefinitionService;
import org.jbpm.services.api.DeploymentService;
import org.jbpm.services.api.ProcessService;
import org.jbpm.services.api.RuntimeDataService;
import org.jbpm.services.api.UserTaskService;
import org.jbpm.services.api.admin.ProcessInstanceAdminService;
import org.jbpm.services.api.admin.ProcessInstanceMigrationService;
import org.jbpm.services.api.admin.UserTaskAdminService;
import org.jbpm.services.api.query.QueryService;
import org.jbpm.services.task.HumanTaskServiceFactory;
import org.jbpm.services.task.audit.TaskAuditServiceFactory;
import org.jbpm.services.task.identity.DefaultUserInfo;
import org.jbpm.shared.services.impl.TransactionalCommandService;
import org.jbpm.springboot.quartz.SpringConnectionProvider;
import org.jbpm.springboot.security.SpringSecurityIdentityProvider;
import org.jbpm.springboot.security.SpringSecurityUserGroupCallback;
import org.jbpm.springboot.services.SpringKModuleDeploymentService;
import org.kie.api.event.process.ProcessEventListener;
import org.kie.api.event.rule.AgendaEventListener;
import org.kie.api.event.rule.RuleRuntimeEventListener;
import org.kie.api.executor.ExecutorService;
import org.kie.api.runtime.Environment;
import org.kie.api.runtime.EnvironmentName;
import org.kie.api.runtime.manager.RuntimeManagerFactory;
import org.kie.api.runtime.process.WorkItemHandler;
import org.kie.api.task.TaskLifeCycleEventListener;
import org.kie.api.task.TaskService;
import org.kie.api.task.UserGroupCallback;
import org.kie.internal.identity.IdentityProvider;
import org.kie.internal.runtime.conf.ObjectModelResolver;
import org.kie.internal.runtime.conf.ObjectModelResolverProvider;
import org.kie.internal.task.api.UserInfo;
import org.kie.spring.jbpm.services.SpringTransactionalCommandService;
import org.kie.spring.manager.SpringRuntimeManagerFactoryImpl;
import org.kie.spring.persistence.KieSpringTransactionManager;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.orm.jpa.JpaProperties;
import org.springframework.boot.bind.RelaxedDataBinder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DatabaseDriver;
import org.springframework.boot.jta.XADataSourceWrapper;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.MutablePersistenceUnitInfo;
import org.springframework.orm.jpa.persistenceunit.PersistenceUnitPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

@Configuration
@ConditionalOnClass({ KModuleDeploymentService.class })
@EnableConfigurationProperties({JBPMProperties.class, DataSourceProperties.class})
public class JBPMAutoConfiguration {
    
    protected static final String PERSISTENCE_UNIT_NAME = "org.jbpm.domain";
    protected static final String PERSISTENCE_XML_LOCATION = "classpath:/META-INF/jbpm-persistence.xml";
    
    private static final String CLASS_RESOURCE_PATTERN = "/**/*.class"; 
    private static final String PACKAGE_INFO_SUFFIX = ".package-info";
    
    private static final String QUARTZ_PROPS = "org.quartz.properties";
    private static final String QUARTZ_FAILED_DELAY = "org.jbpm.timer.quartz.delay";
    private static final String QUARTZ_FAILED_RETRIES = "org.jbpm.timer.quartz.retries";
    private static final String QUARTZ_RESECHEDULE_DELAY = "org.jbpm.timer.quartz.reschedule.delay";
    private static final String QUARTZ_START_DELAY = "org.jbpm.timer.delay";

    private XADataSource xaDataSource;
    private XADataSourceWrapper wrapper;
    
    private JBPMProperties properties;
    
    private PlatformTransactionManager transactionManager;
 
    public JBPMAutoConfiguration(XADataSourceWrapper wrapper, 
                                 PlatformTransactionManager transactionManager,
                                 JBPMProperties properties,
                                 ApplicationContext applicationContext) {
        
        this.wrapper = wrapper;
        this.transactionManager = transactionManager;
        this.properties = properties;
        // init any spring based ObjectModelResolvers
        List<ObjectModelResolver> resolvers = ObjectModelResolverProvider.getResolvers();
        if (resolvers != null) {
            for (ObjectModelResolver resolver : resolvers) {
                if (resolver instanceof ApplicationContextAware) {
                    ((ApplicationContextAware) resolver).setApplicationContext(applicationContext);
                }
            }
        }
        if (properties.getQuartz().isEnabled()) {
            SpringConnectionProvider.setApplicationContext(applicationContext);
        }
    }  
    
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSource dataSource() throws Exception {
        this.xaDataSource = createXaDataSource();        
        return this.wrapper.wrapDataSource(xaDataSource);
    }    
    
    @Bean
    @ConditionalOnMissingBean(name = "entityManagerFactory")
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource, JpaProperties jpaProperties){
        LocalContainerEntityManagerFactoryBean factoryBean = new LocalContainerEntityManagerFactoryBean();
        factoryBean.setPersistenceUnitName(PERSISTENCE_UNIT_NAME);
        factoryBean.setPersistenceXmlLocation(PERSISTENCE_XML_LOCATION);
        factoryBean.setJtaDataSource(dataSource);
        factoryBean.setJpaPropertyMap(jpaProperties.getProperties());

        String packagesToScan = jpaProperties.getProperties().get("entity-scan-packages");
        if (packagesToScan != null) {
            factoryBean.setPersistenceUnitPostProcessors(new PersistenceUnitPostProcessor() {
                
                @Override
                public void postProcessPersistenceUnitInfo(MutablePersistenceUnitInfo pui) {
                    Set<TypeFilter> entityTypeFilters = new LinkedHashSet<TypeFilter>(3);
                    entityTypeFilters.add(new AnnotationTypeFilter(Entity.class, false));
                    entityTypeFilters.add(new AnnotationTypeFilter(Embeddable.class, false));
                    entityTypeFilters.add(new AnnotationTypeFilter(MappedSuperclass.class, false));
                    
                    ResourcePatternResolver resourcePatternResolver = new PathMatchingResourcePatternResolver();
                    
                    if (packagesToScan != null) {
                        for (String pkg : packagesToScan.split(",")) {
                            try {
                                String pattern = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX +
                                        ClassUtils.convertClassNameToResourcePath(pkg) + CLASS_RESOURCE_PATTERN;
                                Resource[] resources = resourcePatternResolver.getResources(pattern);
                                MetadataReaderFactory readerFactory = new CachingMetadataReaderFactory(resourcePatternResolver);
                                for (Resource resource : resources) {
                                    if (resource.isReadable()) {
                                        MetadataReader reader = readerFactory.getMetadataReader(resource);
                                        String className = reader.getClassMetadata().getClassName();
                                        if (matchesFilter(reader, readerFactory, entityTypeFilters)) {
                                            pui.addManagedClassName(className);
                                        } else if (className.endsWith(PACKAGE_INFO_SUFFIX)) {
                                            pui.addManagedPackage(className.substring(0, className.length() - PACKAGE_INFO_SUFFIX.length()));
                                        }
                                    }
                                }
                            }
                            catch (IOException ex) {
                                throw new PersistenceException("Failed to scan classpath for unlisted entity classes", ex);
                            }
                        }
                    }
                    
                }
                
                private boolean matchesFilter(MetadataReader reader, MetadataReaderFactory readerFactory, Set<TypeFilter> entityTypeFilters) throws IOException {
                    for (TypeFilter filter : entityTypeFilters) {
                        if (filter.match(reader, readerFactory)) {
                            return true;
                        }
                    }
                    return false;
                }
            });
        }
   
        return factoryBean;
    }
    
    @Bean
    @ConditionalOnMissingBean(name = "identityProvider")
    public IdentityProvider identityProvider() {
        
        return new SpringSecurityIdentityProvider();
    }
    
    @Bean
    @ConditionalOnMissingBean(name = "kieTransactionManager")
    public TransactionManager kieTransactionManager() {
        
        return new KieSpringTransactionManager((AbstractPlatformTransactionManager) transactionManager);
    }
    
    @Bean
    @ConditionalOnMissingBean(name = "userGroupCallback")
    public UserGroupCallback userGroupCallback(IdentityProvider identityProvider) throws IOException {
        return new SpringSecurityUserGroupCallback(identityProvider);
    }
    
    @Bean
    @ConditionalOnMissingBean(name = "userInfo")
    public UserInfo userInfo() throws IOException {
        Resource resource = new ClassPathResource("/userinfo.properties");
        Properties userInfo = PropertiesLoaderUtils.loadProperties(resource);
        return new DefaultUserInfo(userInfo);
    }
    
    @Bean
    @ConditionalOnMissingBean(name = "definitionService")
    public DefinitionService definitionService() {
        
        return new BPMN2DataServiceImpl();
    }
    
    @Bean
    @ConditionalOnMissingBean(name = "formService")
    public FormManagerService formService() {
        
        return new FormManagerServiceImpl();
    }
    
    @Bean
    @ConditionalOnMissingBean(name = "runtimeManagerFactory")
    public RuntimeManagerFactory runtimeManagerFactory(UserGroupCallback userGroupCallback, UserInfo userInfo) {
        
        SpringRuntimeManagerFactoryImpl runtimeManager = new SpringRuntimeManagerFactoryImpl();
        runtimeManager.setTransactionManager((AbstractPlatformTransactionManager) transactionManager);
        runtimeManager.setUserGroupCallback(userGroupCallback);
        runtimeManager.setUserInfo(userInfo);
        
        if (properties.getQuartz().isEnabled()) {
            System.setProperty(QUARTZ_PROPS, properties.getQuartz().getConfiguration());
            System.setProperty(QUARTZ_FAILED_DELAY, String.valueOf(properties.getQuartz().getFailedJobDelay()));
            System.setProperty(QUARTZ_FAILED_RETRIES, String.valueOf(properties.getQuartz().getFailedJobRetry()));
            System.setProperty(QUARTZ_RESECHEDULE_DELAY, String.valueOf(properties.getQuartz().getRescheduleDelay()));
            System.setProperty(QUARTZ_START_DELAY, String.valueOf(properties.getQuartz().getStartDelay()));
        } else {
            System.clearProperty(QUARTZ_PROPS);
            System.clearProperty(QUARTZ_FAILED_DELAY);
            System.clearProperty(QUARTZ_FAILED_RETRIES);
            System.clearProperty(QUARTZ_RESECHEDULE_DELAY);
            System.clearProperty(QUARTZ_START_DELAY);
        }
        
        return runtimeManager;
    }
    
    @Bean
    @ConditionalOnMissingBean(name = "transactionalCommandService")
    public TransactionalCommandService transactionalCommandService(EntityManagerFactory entityManagerFactory, TransactionManager kieTransactionManager) {
        
        return new SpringTransactionalCommandService(entityManagerFactory, kieTransactionManager, (AbstractPlatformTransactionManager) transactionManager);
    }
    
    @SuppressWarnings("unchecked")
    @Bean(destroyMethod="shutdown")
    @ConditionalOnMissingBean(name = "deploymentService")
    public DeploymentService deploymentService(DefinitionService definitionService, RuntimeManagerFactory runtimeManagerFactory, FormManagerService formService, EntityManagerFactory entityManagerFactory, IdentityProvider identityProvider, 
            Optional<List<WorkItemHandler>> handlers,
            Optional<List<ProcessEventListener>> processEventListeners,
            Optional<List<AgendaEventListener>> agendaEventListeners,
            Optional<List<RuleRuntimeEventListener>> ruleRuntimeEventListeners,
            Optional<List<TaskLifeCycleEventListener>> taskListeners,
            Optional<List<CaseEventListener>> caseEventListeners
            ) {
        
        EntityManagerFactoryManager.get().addEntityManagerFactory(PERSISTENCE_UNIT_NAME, entityManagerFactory);
        
        SpringKModuleDeploymentService deploymentService = new SpringKModuleDeploymentService();
        ((SpringKModuleDeploymentService) deploymentService).setBpmn2Service(definitionService);
        ((SpringKModuleDeploymentService) deploymentService).setEmf(entityManagerFactory);
        ((SpringKModuleDeploymentService) deploymentService).setIdentityProvider(identityProvider);
        ((SpringKModuleDeploymentService) deploymentService).setManagerFactory(runtimeManagerFactory);
        ((SpringKModuleDeploymentService) deploymentService).setFormManagerService(formService);
        
        ((SpringKModuleDeploymentService) deploymentService).registerWorkItemHandlers((List<WorkItemHandler>) extractFromOptional(handlers));        
        ((SpringKModuleDeploymentService) deploymentService).registerAgendaEventListeners((List<AgendaEventListener>) extractFromOptional(agendaEventListeners));        
        ((SpringKModuleDeploymentService) deploymentService).registerCaseEventListeners((List<CaseEventListener>) extractFromOptional(caseEventListeners));
        ((SpringKModuleDeploymentService) deploymentService).registerProcessEventListeners((List<ProcessEventListener>) extractFromOptional(processEventListeners));
        ((SpringKModuleDeploymentService) deploymentService).registerRuleRuntimeEventListeners((List<RuleRuntimeEventListener>) extractFromOptional(ruleRuntimeEventListeners));
        ((SpringKModuleDeploymentService) deploymentService).registerTaskListeners((List<TaskLifeCycleEventListener>) extractFromOptional(taskListeners));        
        
        ((SpringKModuleDeploymentService) deploymentService).addListener(((BPMN2DataServiceImpl) definitionService));
        
        return deploymentService;
    }
    
    @Bean
    @ConditionalOnMissingBean(name = "runtimeDataService")
    public RuntimeDataService runtimeDataService(EntityManagerFactory entityManagerFactory, UserGroupCallback userGroupCallback, UserInfo userInfo, TransactionalCommandService transactionalCommandService, IdentityProvider identityProvider, DeploymentService deploymentService) {
        
        Environment environment = EnvironmentFactory.newEnvironment();
        environment.set(EnvironmentName.TRANSACTION_MANAGER, transactionManager);
        environment.set(EnvironmentName.ENTITY_MANAGER_FACTORY, entityManagerFactory);
        
        TaskService taskService = HumanTaskServiceFactory.newTaskServiceConfigurator()
                .entityManagerFactory(entityManagerFactory)
                .userGroupCallback(userGroupCallback)
                .userInfo(userInfo)
                .environment(environment)
                .getTaskService();

        // build runtime data service
        RuntimeDataServiceImpl runtimeDataService = new RuntimeDataServiceImpl();
        runtimeDataService.setCommandService(transactionalCommandService);
        runtimeDataService.setIdentityProvider(identityProvider);
        runtimeDataService.setUserGroupCallback(userGroupCallback);
        runtimeDataService.setTaskService(taskService);
        runtimeDataService.setTaskAuditService(TaskAuditServiceFactory.newTaskAuditServiceConfigurator()
                .setTaskService(taskService)
                .getTaskAuditService());
        
        ((KModuleDeploymentService) deploymentService).setRuntimeDataService(runtimeDataService);
        ((KModuleDeploymentService) deploymentService).addListener(runtimeDataService);
        
        return runtimeDataService;
    }
    
    @Bean
    @ConditionalOnMissingBean(name = "processService")
    public ProcessService processService(RuntimeDataService runtimeDataService, DeploymentService deploymentService) {
        
        ProcessServiceImpl processService = new ProcessServiceImpl();
        processService.setDataService(runtimeDataService);
        processService.setDeploymentService(deploymentService);
        
        return processService;
    }
    
    @Bean
    @ConditionalOnMissingBean(name = "userTaskService")
    public UserTaskService userTaskService(RuntimeDataService runtimeDataService, DeploymentService deploymentService) {
        
        UserTaskServiceImpl userTaskService = new UserTaskServiceImpl();
        ((UserTaskServiceImpl) userTaskService).setDataService(runtimeDataService);
        ((UserTaskServiceImpl) userTaskService).setDeploymentService(deploymentService);
        
        return userTaskService;
    }
    
    @Bean
    @ConditionalOnMissingBean(name = "queryService")
    public QueryService queryService(DataSource dataSource, TransactionalCommandService transactionalCommandService, IdentityProvider identityProvider, DeploymentService deploymentService, UserGroupCallback userGroupCallback) {
        
        QueryServiceImpl queryService = new QueryServiceImpl();
        queryService.setIdentityProvider(identityProvider);
        queryService.setCommandService(transactionalCommandService);
        queryService.setUserGroupCallback(userGroupCallback);
        // override data source locator to not use JNDI
        SQLDataSetProvider sqlDataSetProvider = SQLDataSetProvider.get();
        sqlDataSetProvider.setDataSourceLocator(new SQLDataSourceLocator() {
                        
            @Override
            public DataSource lookup(SQLDataSetDef def) throws Exception {
                return dataSource;
            }
        });
        
        queryService.init();
        ((KModuleDeploymentService) deploymentService).addListener(queryService);
        
        return queryService;
    }
    
    @Bean
    @ConditionalOnMissingBean(name = "processInstanceMigrationService")
    public ProcessInstanceMigrationService processInstanceMigrationService() {
        
        return new ProcessInstanceMigrationServiceImpl();
    }
    
    @Bean
    @ConditionalOnMissingBean(name = "processInstanceAdminService")
    public ProcessInstanceAdminService processInstanceAdminService(RuntimeDataService runtimeDataService, ProcessService processService, TransactionalCommandService transactionalCommandService, IdentityProvider identityProvider) {
        ProcessInstanceAdminServiceImpl processInstanceAdminService = new ProcessInstanceAdminServiceImpl();
        processInstanceAdminService.setProcessService(processService);
        processInstanceAdminService.setRuntimeDataService(runtimeDataService);
        processInstanceAdminService.setCommandService(transactionalCommandService);
        processInstanceAdminService.setIdentityProvider(identityProvider);
        
        return processInstanceAdminService;
    }
    
    @Bean
    @ConditionalOnMissingBean(name = "userTaskAdminService")
    public UserTaskAdminService userTaskAdminService(RuntimeDataService runtimeDataService, UserTaskService userTaskService, TransactionalCommandService transactionalCommandService, IdentityProvider identityProvider) {
        UserTaskAdminServiceImpl userTaskAdminService = new UserTaskAdminServiceImpl();
        userTaskAdminService.setRuntimeDataService(runtimeDataService);
        userTaskAdminService.setUserTaskService(userTaskService);
        userTaskAdminService.setIdentityProvider(identityProvider);
        userTaskAdminService.setCommandService(transactionalCommandService);        
        
        return userTaskAdminService;
    }
    
    @Bean
    @ConditionalOnMissingBean(name = "executorService")
    @ConditionalOnProperty(name = "jbpm.executor.enabled")
    public ExecutorService executorService(EntityManagerFactory entityManagerFactory, TransactionalCommandService transactionalCommandService, DeploymentService deploymentService) {
        
        ExecutorEventSupportImpl eventSupport = new ExecutorEventSupportImpl();
        // configure services
        ExecutorService service = ExecutorServiceFactory.newExecutorService(entityManagerFactory, transactionalCommandService, eventSupport);
        
        service.setInterval(properties.getExecutor().getInterval());
        service.setRetries(properties.getExecutor().getRetries());
        service.setThreadPoolSize(properties.getExecutor().getThreadPoolSize());
        service.setTimeunit(TimeUnit.valueOf(properties.getExecutor().getTimeUnit()));

        ((KModuleDeploymentService) deploymentService).setExecutorService(service);
        
        return service;
    }
    
    @Bean
    @ConditionalOnMissingBean(name = "caseIdGenerator")
    public CaseIdGenerator caseIdGenerator(TransactionalCommandService transactionalCommandService) {
        
        return new TableCaseIdGenerator(transactionalCommandService);
    }
    
    @Bean
    @ConditionalOnClass({ CaseRuntimeDataServiceImpl.class })
    @ConditionalOnMissingBean(name = "caseRuntimeService")
    public CaseRuntimeDataService caseRuntimeService(CaseIdGenerator caseIdGenerator, RuntimeDataService runtimeDataService, DeploymentService deploymentService, TransactionalCommandService transactionalCommandService, IdentityProvider identityProvider) {
        
        CaseRuntimeDataServiceImpl caseRuntimeDataService = new CaseRuntimeDataServiceImpl();
        caseRuntimeDataService.setCaseIdGenerator(caseIdGenerator);
        caseRuntimeDataService.setRuntimeDataService(runtimeDataService);
        caseRuntimeDataService.setCommandService(transactionalCommandService);
        caseRuntimeDataService.setIdentityProvider(identityProvider);
        
        // configure case mgmt services as listeners
        ((KModuleDeploymentService)deploymentService).addListener(caseRuntimeDataService);
        
        return caseRuntimeDataService;
    }
    
    @Bean
    @ConditionalOnClass({ CaseServiceImpl.class })
    @ConditionalOnMissingBean(name = "caseService")
    public CaseService caseService(CaseIdGenerator caseIdGenerator, CaseRuntimeDataService caseRuntimeDataService, RuntimeDataService runtimeDataService, ProcessService processService, DeploymentService deploymentService, TransactionalCommandService transactionalCommandService, IdentityProvider identityProvider) {
        CaseServiceImpl caseService = new CaseServiceImpl();
        caseService.setCaseIdGenerator(caseIdGenerator);
        caseService.setCaseRuntimeDataService(caseRuntimeDataService);
        caseService.setProcessService(processService);
        caseService.setDeploymentService(deploymentService);
        caseService.setRuntimeDataService(runtimeDataService);
        caseService.setCommandService(transactionalCommandService);
        caseService.setAuthorizationManager(new AuthorizationManagerImpl(identityProvider, transactionalCommandService));
        caseService.setIdentityProvider(identityProvider);
        
        // build case configuration on deployment listener
        CaseConfigurationDeploymentListener configurationListener = new CaseConfigurationDeploymentListener(identityProvider, transactionalCommandService);

        // configure case mgmt services as listeners        
        ((KModuleDeploymentService)deploymentService).addListener(configurationListener);
        
        return caseService;
    }
    
    @Bean
    @ConditionalOnClass({ CaseInstanceMigrationServiceImpl.class })
    @ConditionalOnMissingBean(name = "caseInstanceMigrationService")
    public CaseInstanceMigrationService caseInstanceMigrationService(EntityManagerFactory entityManagerFactory, CaseRuntimeDataService caseRuntimeDataService, ProcessService processService, ProcessInstanceMigrationService processInstanceMigrationService) {
        CaseInstanceMigrationServiceImpl caseInstanceMigrationService = new CaseInstanceMigrationServiceImpl();
        caseInstanceMigrationService.setCaseRuntimeDataService(caseRuntimeDataService);
        caseInstanceMigrationService.setCommandService(new TransactionalCommandService(entityManagerFactory));
        caseInstanceMigrationService.setProcessInstanceMigrationService(processInstanceMigrationService);
        caseInstanceMigrationService.setProcessService(processService);
        
        return caseInstanceMigrationService;
    }
    
    /*
     * Optional quartz configuration - by default same data source is used for transactional Quartz work
     * and new one (from properties quartz.datasource) for unmanaged access
     */
    
    @Bean
    @ConditionalOnMissingBean(name = "quartzDataSource")
    @ConditionalOnProperty(name = {"jbpm.quartz.enabled", "jbpm.quartz.db"}, havingValue="true")
    public DataSource quartzDataSource(DataSource dataSource) {
        return dataSource;
    }
    
    @Bean
    @ConditionalOnMissingBean(name = "quartzDatasourceProperties")
    @ConfigurationProperties("quartz.datasource")
    public DataSourceProperties quartzDatasourceProperties() {
        return new DataSourceProperties();
    }
    
    @Bean
    @ConditionalOnMissingBean(name = "quartzPoolProperties")
    @ConfigurationProperties("quartz.datasource.dbcp2")
    public Map<String, Object> quartzPoolProperties() {
        return new HashMap<>();
    }

    @Bean
    @ConditionalOnMissingBean(name = "quartzNotManagedDataSource")
    @ConditionalOnProperty(name = {"jbpm.quartz.enabled", "jbpm.quartz.db"}, havingValue="true")
    public DataSource quartzNotManagedDataSource() {
        DataSource ds = quartzDatasourceProperties().initializeDataSourceBuilder().build();
        Map<String, Object> poolProperties = quartzPoolProperties();
        
        MutablePropertyValues properties = new MutablePropertyValues(poolProperties);
        new RelaxedDataBinder(ds).bind(properties);
        
        return ds;
    }
    
    /*
     * Helper methods
     */

    private XADataSource createXaDataSource() {
        DataSourceProperties dataSourceProperties = dataSourceProperties();
        
        String className = dataSourceProperties.getXa().getDataSourceClassName();
        if (!StringUtils.hasLength(className)) {
            className = DatabaseDriver.fromJdbcUrl(dataSourceProperties.determineUrl())
                    .getXaDataSourceClassName();
        }
        Assert.state(StringUtils.hasLength(className),
                "No XA DataSource class name specified");
        XADataSource dataSource = createXaDataSourceInstance(className);
        bindXaProperties(dataSource, dataSourceProperties);
        return dataSource;
    }

    private XADataSource createXaDataSourceInstance(String className) {
        try {
            Class<?> dataSourceClass = ClassUtils.forName(className, this.getClass().getClassLoader());
            Object instance = BeanUtils.instantiate(dataSourceClass);
            Assert.isInstanceOf(XADataSource.class, instance);
            return (XADataSource) instance;
        }
        catch (Exception ex) {
            throw new IllegalStateException(
                    "Unable to create XADataSource instance from '" + className + "'", ex);
        }
    }

    private void bindXaProperties(XADataSource target, DataSourceProperties properties) {
        MutablePropertyValues values = new MutablePropertyValues();
        values.add("user", properties.determineUsername());
        values.add("password", properties.determinePassword());
        values.add("url", properties.determineUrl());
        values.addPropertyValues(properties.getXa().getProperties());
        new RelaxedDataBinder(target).withAlias("user", "username").bind(values);
    }
    
    protected Object extractFromOptional(Optional<?> optionalList) {
        if (optionalList.isPresent()) {
            return optionalList.get();
        }
        
        return Collections.emptyList();
    }
}
