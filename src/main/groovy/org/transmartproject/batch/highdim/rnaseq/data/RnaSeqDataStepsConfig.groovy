package org.transmartproject.batch.highdim.rnaseq.data

import groovy.util.logging.Slf4j
import org.springframework.batch.core.Step
import org.springframework.batch.core.StepContribution
import org.springframework.batch.core.configuration.annotation.JobScope
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.scope.context.JobSynchronizationManager
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.core.step.tasklet.TaskletStep
import org.springframework.batch.item.ItemProcessor
import org.springframework.batch.item.ItemStreamReader
import org.springframework.batch.item.ItemWriter
import org.springframework.batch.item.validator.ValidatingItemProcessor
import org.springframework.batch.repeat.RepeatStatus
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.transmartproject.batch.batchartifacts.CollectMinimumPositiveValueListener
import org.transmartproject.batch.batchartifacts.MultipleItemsLineItemReader
import org.transmartproject.batch.beans.JobScopeInterfaced
import org.transmartproject.batch.beans.StepBuildingConfigurationTrait
import org.transmartproject.batch.clinical.db.objects.Tables
import org.transmartproject.batch.db.DatabaseImplementationClassPicker
import org.transmartproject.batch.db.DbConfig
import org.transmartproject.batch.db.DeleteByColumnValueWriter
import org.transmartproject.batch.db.oracle.OraclePartitionTasklet
import org.transmartproject.batch.db.postgres.ApplyConstraintsTasklet
import org.transmartproject.batch.db.postgres.CreateAssayBasedPartitionTableTasklet
import org.transmartproject.batch.highdim.assays.AssayStepsConfig
import org.transmartproject.batch.highdim.assays.CurrentAssayIdsReader
import org.transmartproject.batch.highdim.datastd.*
import org.transmartproject.batch.highdim.jobparams.StandardHighDimDataParametersModule
import org.transmartproject.batch.startup.StudyJobParametersModule
import org.transmartproject.batch.support.JobParameterFileResource

/**
 * Spring batch steps configuration for RNASeq data upload
 */
@Configuration
@ComponentScan
@Import([DbConfig, AssayStepsConfig])
@Slf4j
class RnaSeqDataStepsConfig implements StepBuildingConfigurationTrait {

    static int dataFilePassChunkSize = 10000

    @Autowired
    DatabaseImplementationClassPicker picker

    @Bean
    Step firstPass(RnaSeqDataValueValidator rnaSeqDataValueValidator) {
        CollectMinimumPositiveValueListener minPosValueColector = collectMinimumPositiveValueListener()
        TaskletStep step = steps.get('firstPass')
                .chunk(dataFilePassChunkSize)
                .reader(rnaSeqDataTsvFileReader())
                .processor(compositeOf(
                new ValidatingItemProcessor(adaptValidator(rnaSeqDataValueValidator)),
                new NegativeDataPointWarningProcessor(),
        ))
                .stream(minPosValueColector)
                .listener(minPosValueColector)
                .listener(logCountsStepListener())
                .build()

        wrapStepWithName('firstPass', step)
    }

    @Bean
    Step deleteHdData(CurrentAssayIdsReader currentAssayIdsReader) {
        steps.get('deleteHdData')
                .chunk(100)
                .reader(currentAssayIdsReader)
                .writer(deleteRnaSeqDataWriter())
                .build()
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
    Step secondPass(ItemWriter<RnaSeqDataValue> rnaSeqDataWriter,
                    ItemProcessor compositeOfRnaSeqSecondPassProcessors) {
        TaskletStep step = steps.get('secondPass')
                .chunk(dataFilePassChunkSize)
                .reader(rnaSeqDataTsvFileReader())
                .processor(compositeOfRnaSeqSecondPassProcessors)
                .writer(rnaSeqDataWriter)
                .listener(logCountsStepListener())
                .listener(progressWriteListener())
                .build()

        step
    }

    @Bean
    @JobScopeInterfaced
    ItemProcessor<TripleStandardDataValue, TripleStandardDataValue> compositeOfRnaSeqSecondPassProcessors(
            @Value("#{jobParameters['ZERO_MEANS_NO_INFO']}") String zeroMeansNoInfo,
            @Value("#{jobParameters['SKIP_UNMAPPED_DATA']}") String skipUnmappedData) {
        def processors = []
        if (zeroMeansNoInfo == 'Y') {
            processors << new FilterZerosItemProcessor()
        }
        if (skipUnmappedData == 'Y') {
            processors << filterDataWithoutAssayMappingsItemProcessor()
        }
        processors << patientInjectionProcessor()
        processors << tripleStandardDataValueLogCalculationProcessor()

        compositeOf(*processors)
    }

    @Bean
    @JobScope
    CollectMinimumPositiveValueListener collectMinimumPositiveValueListener() {
        new CollectMinimumPositiveValueListener(minPositiveValueRequired: false)
    }

    @Bean
    @JobScope
    FilterDataWithoutAssayMappingsItemProcessor filterDataWithoutAssayMappingsItemProcessor() {
        new FilterDataWithoutAssayMappingsItemProcessor()
    }

    @Bean
    @JobScope
    PatientInjectionProcessor patientInjectionProcessor() {
        new PatientInjectionProcessor()
    }

    @Bean
    @JobScope
    TripleStandardDataValueLogCalculationProcessor tripleStandardDataValueLogCalculationProcessor() {
        new TripleStandardDataValueLogCalculationProcessor()
    }

    @Bean
    @JobScopeInterfaced
    org.springframework.core.io.Resource dataFileResource() {
        new JobParameterFileResource(
                parameter: StandardHighDimDataParametersModule.DATA_FILE)
    }

    @Bean
    ItemStreamReader rnaSeqDataTsvFileReader(
            RnaSeqDataMultipleVariablesPerSampleFieldSetMapper rnaSeqDataMultipleSamplesFieldSetMapper) {
        new MultipleItemsLineItemReader(
                resource: dataFileResource(),
                multipleItemsFieldSetMapper: rnaSeqDataMultipleSamplesFieldSetMapper
        )
    }

    @Bean
    @JobScopeInterfaced
    Tasklet partitionTasklet() {
        switch (picker.pickClass(CreateAssayBasedPartitionTableTasklet, OraclePartitionTasklet)) {
            case CreateAssayBasedPartitionTableTasklet:
                return new CreateAssayBasedPartitionTableTasklet(
                        tableName: Tables.RNASEQ_DATA,
                )
            case OraclePartitionTasklet:
                String studyId = JobSynchronizationManager.context
                        .jobParameters[StudyJobParametersModule.STUDY_ID]
                assert studyId != null

                return new OraclePartitionTasklet(
                        tableName: Tables.RNASEQ_DATA,
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
                return new ApplyConstraintsTasklet(primaryKey: ['assay_id', 'region_id'])
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

    @Bean
    DeleteByColumnValueWriter<Long> deleteRnaSeqDataWriter() {
        new DeleteByColumnValueWriter<Long>(
                table: Tables.RNASEQ_DATA,
                column: 'assay_id')
    }

}
