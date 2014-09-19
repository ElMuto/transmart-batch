package org.transmartproject.batch.clinical

import org.springframework.batch.item.ItemProcessor
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.transmartproject.batch.model.*
import org.transmartproject.batch.support.DatabaseObject
import org.transmartproject.batch.support.SequenceReserver

import javax.annotation.PostConstruct

/**
 * Converts Rows into FactRowSets</br>
 * This includes resolving patients and concepts, reserving ids for the new ones.
 */
class RowToFactRowSetConverter implements ItemProcessor<Row, FactRowSet> {

    @Autowired
    SequenceReserver sequenceReserver

    @Value("#{jobParameters['studyId']}")
    String studyId

    @Value("#{clinicalJobContext.patientSet}")
    PatientSet patientSet

    @Value("#{clinicalJobContext.variables}")
    List<Variable> variables

    private Map<String, FileVariables> variablesMap

    @Override
    FactRowSet process(Row item) throws Exception {
        FileVariables vars = variablesMap.get(item.filename)
        Patient patient = getPatient(item, vars)
        vars.create(studyId, item, patient, sequenceReserver)
    }

    @PostConstruct
    void init() {

        Map<String,List<Variable>> map = variables.groupBy { it.filename }
        this.variablesMap = map.collectEntries { [(it.key): FileVariables.create(it.value)] }
    }

    private Patient getPatient(Row item, FileVariables vars) {
        Patient patient = patientSet.getPatient(vars.getPatientId(item))
        patient.demographicValues.putAll(vars.getDemographicRelatedValues(item))

        if (!patient.code) {
            //new patient: reserve code
            patient.code = sequenceReserver.getNext(DatabaseObject.Sequence.PATIENT)
            //println "reserved patient code $patient.code"
        }
        patient
    }
}

/**
 * Variables defined for a file
 */
class FileVariables {
    Variable subjectIdVariable
    Variable siteIdVariable
    Variable visitNameVariable
    List<Variable> otherVariables = []
    List<Variable> demographicRelated = []

    static FileVariables create(List<Variable> list) {
        def otherVariables = []
        def args = [:]
        def demographic = []
        list.each {
            switch (it.dataLabel) {
                case Variable.SUBJ_ID:
                    args.put('subjectIdVariable', it)
                    break
                case Variable.SITE_ID:
                    args.put('siteIdVariable', it)
                    break
                case Variable.VISIT_NAME:
                    args.put('visitNameVariable', it)
                    break
                default:
                    otherVariables.add(it)
            }
            if (it.demographicVariable) {
                demographic.add(it)
            }
        }
        args.put('otherVariables', otherVariables)
        args.put('demographicRelated', demographic)

        new FileVariables(args)
    }

    String getPatientId(Row row) {
        row.values.get(subjectIdVariable.columnNumber)
    }

    FactRowSet create(String studyId, Row row, Patient patient, SequenceReserver reserver) {
        FactRowSet result = new FactRowSet()
        result.studyId = studyId
        result.patient = patient

        if (siteIdVariable) {
            result.siteId = row.values.get(siteIdVariable.columnNumber)
        }
        if (visitNameVariable) {
            result.visitName = row.values.get(visitNameVariable.columnNumber)
        }

        otherVariables.each {
            String value = row.values.get(it.columnNumber)
            ConceptNode concept = result.addValue(it, value)

            ConceptNode tmp = concept
            //goes up in the concept hierarchy, reserving codes until no longer necessary
            while (tmp && !tmp.code) {
                //new concept: reserve code
                tmp.code = reserver.getNext(DatabaseObject.Sequence.CONCEPT)
                tmp.i2b2RecordId = reserver.getNext(DatabaseObject.Sequence.I2B2_RECORDID)
                //println "reserved concept code $tmp.code"
                tmp = tmp.parent //recurse to parent
            }
        }

        result
    }

    Map<Variable, Object> getDemographicRelatedValues(Row row) {
        demographicRelated.collectEntries { [(it), row.values[it.columnNumber]] }
    }

}