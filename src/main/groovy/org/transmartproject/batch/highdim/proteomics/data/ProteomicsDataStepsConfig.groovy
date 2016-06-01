package org.transmartproject.batch.highdim.proteomics.data

import groovy.util.logging.Slf4j
import org.springframework.batch.core.Step
import org.springframework.batch.core.StepContribution
import org.springframework.batch.core.configuration.annotation.JobScope
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.scope.context.JobSynchronizationManager
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.item.ItemWriter
import org.springframework.batch.repeat.RepeatStatus
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.transmartproject.batch.beans.JobScopeInterfaced
import org.transmartproject.batch.clinical.db.objects.Tables
import org.transmartproject.batch.db.DatabaseImplementationClassPicker
import org.transmartproject.batch.db.DbConfig
import org.transmartproject.batch.db.DeleteByColumnValueWriter
import org.transmartproject.batch.db.oracle.OraclePartitionTasklet
import org.transmartproject.batch.db.postgres.ApplyConstraintsTasklet
import org.transmartproject.batch.db.postgres.CreateAssayBasedPartitionTableTasklet
import org.transmartproject.batch.highdim.beans.AbstractTypicalHdDataStepsConfig
import org.transmartproject.batch.startup.StudyJobParametersModule

/**
 * Spring context for proteomics data loading steps.
 */
@Configuration
@ComponentScan
@Import(DbConfig)
@Slf4j
class ProteomicsDataStepsConfig extends AbstractTypicalHdDataStepsConfig {

    @Autowired
    DatabaseImplementationClassPicker picker

    @Bean
    @Override
    ItemWriter getDeleteCurrentDataWriter() {
        new DeleteByColumnValueWriter<Long>(
                table: Tables.PROTEOMICS_DATA,
                column: 'assay_id',
                entityName: 'proteomics data points')
    }


    @Bean
    Step partitionDataTable() {
        stepOf('partitionDataTable', partitionTasklet())
    }

    @Bean
    Step applyConstraintsToPartitionDataTable() {
        stepOf('applyConstraintsToPartitionDataTable', applyConstraintsTasklet())
    }

    @Bean
    @Override
    @JobScope
    ProteomicsDataWriter getDataWriter() {
        new ProteomicsDataWriter()
    }

    @Bean
    @JobScopeInterfaced
    Tasklet partitionTasklet() {
        switch (picker.pickClass(CreateAssayBasedPartitionTableTasklet, OraclePartitionTasklet)) {
            case CreateAssayBasedPartitionTableTasklet:
                return new CreateAssayBasedPartitionTableTasklet(
                        tableName: Tables.PROTEOMICS_DATA,
                )
            case OraclePartitionTasklet:
                String studyId = JobSynchronizationManager.context
                        .jobParameters[StudyJobParametersModule.STUDY_ID]
                assert studyId != null

                return new OraclePartitionTasklet(
                        tableName: Tables.PROTEOMICS_DATA,
                        partitionByColumnValue: studyId)
            default:
                informTasklet('No partitioning implementation for this DBMS.')
        }
    }

    @Bean
    @JobScopeInterfaced
    Tasklet applyConstraintsTasklet() {
        switch (picker.pickClass(ApplyConstraintsTasklet)) {
            case ApplyConstraintsTasklet:
                return new ApplyConstraintsTasklet(primaryKey: ['assay_id', 'protein_annotation_id'])
            default:
                informTasklet('No constraints application for this DBMS.')
        }
    }

    @Bean
    Tasklet informTasklet(String message) {
        new Tasklet() {
            @Override
            RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
                log.info(message)
                RepeatStatus.FINISHED
            }
        }
    }
}
