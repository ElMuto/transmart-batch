package org.transmartproject.batch.clinical

import org.springframework.batch.core.StepContribution
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.repeat.RepeatStatus
import org.springframework.beans.factory.annotation.Value
import org.transmartproject.batch.model.WordMapping
import org.transmartproject.batch.support.LineListener
import org.transmartproject.batch.support.LineStepContributionAdapter

/**
 * Tasklet that reads the word map file (if defined) and populates the word mappings list
 */
class ReadWordMapTasklet implements Tasklet {

    @Value("#{jobParameters['dataLocation']}")
    String dataLocation

    @Value("#{jobParameters['wordMapFile']}")
    String wordMapFile

    @Value("#{clinicalJobContext.wordMappings}")
    List<WordMapping> wordMappings

    @Override
    RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        List<WordMapping> list
        File file = getFile()
        if (file) {
            LineListener listener = new LineStepContributionAdapter(contribution)
            list = WordMapping.parse(file.newInputStream(), listener)
            list.each {
                if (it.newValue == 'null') {
                    it.newValue = null //we want the value null, not the string 'null'
                }
            }
        } else {
            list = []
        }

        wordMappings.clear()
        wordMappings.addAll(list)

        return RepeatStatus.FINISHED
    }

    File getFile() {
        if (!wordMapFile) {
            return null
        }
        if ('x'.equalsIgnoreCase(wordMapFile)) {
            //alias for 'not defined'
            return null
        }
        if (!dataLocation) {
            throw new IllegalArgumentException('Data location not defined')
        }
        new File(dataLocation, wordMapFile)
    }

}