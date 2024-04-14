/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.as400.access.AS400;
import com.ibm.as400.access.AS400Bin4;
import com.ibm.as400.access.AS400Bin8;
import com.ibm.as400.access.AS400DataType;
import com.ibm.as400.access.AS400Message;
import com.ibm.as400.access.AS400Structure;
import com.ibm.as400.access.AS400Text;
import com.ibm.as400.access.FileAttributes;
import com.ibm.as400.access.ProgramCall;
import com.ibm.as400.access.ProgramParameter;
import com.ibm.as400.access.QSYSObjectPathName;
import com.ibm.as400.access.ServiceProgramCall;

import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.DetailedJournalReceiver;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.JournalReceiverInfo;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.JournalStatus;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.KeyDecoder;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.KeyHeader;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.ReceiverDecoder;

/**
 * @see http://www.setgetweb.com/p/i5/rzakiwrkjrna.htm
 * @author sillencem
 *
 */
public class JournalInfoRetrieval {
    private static final Logger log = LoggerFactory.getLogger(JournalInfoRetrieval.class);

    public static final String JOURNAL_SERVICE_LIB = "/QSYS.LIB/QJOURNAL.SRVPGM";
    private static final byte[] EMPTY_AS400_TEXT = new AS400Text(0).toBytes("");
    private static final AS400Bin8 AS400_BIN8 = new AS400Bin8();
    private static final AS400Bin4 AS400_BIN4 = new AS400Bin4();
    private static final int KEY_HEADER_LENGTH = 20;
    private final DetailedJournalReceiverCache cache = new DetailedJournalReceiverCache();

    public JournalInfoRetrieval() {
        super();
    }

    public JournalPosition getCurrentPosition(AS400 as400, JournalInfo journalLib, Integer ccsid) throws Exception {
        final JournalReceiver ji = JournalInfoRetrieval.getReceiver(as400, journalLib, ccsid);
        final BigInteger offset = getOffset(as400, ji, ccsid).end();
        return new JournalPosition(offset, ji);
    }

    public DetailedJournalReceiver getCurrentDetailedJournalReceiver(AS400 as400, JournalInfo journalLib, Integer ccsid)
            throws Exception {
        final JournalReceiver ji = JournalInfoRetrieval.getReceiver(as400, journalLib, ccsid);
        return getOffset(as400, ji, ccsid);
    }

    static final Pattern JOURNAL_REGEX = Pattern.compile("\\/[^/]*\\/([^.]*).LIB\\/(.*).JRN");

    @Deprecated
    public static JournalInfo getJournal(AS400 as400, String schema) throws IllegalStateException {
        // https://stackoverflow.com/questions/43061626/as-400-create-journal-jt400-java
        // note each file can have it's own different journal
        try {
            final FileAttributes fa = new FileAttributes(as400, String.format("/QSYS.LIB/%s.LIB", schema));
            final Matcher m = JOURNAL_REGEX.matcher(fa.getJournal());
            if (m.matches()) {
                return new JournalInfo(m.group(2), m.group(1));
            }
            else {
                log.error("no match searching for journal filename {}", fa.getJournal());
            }
        }
        catch (final Exception e) {
            throw new IllegalStateException("Journal not found", e);
        }
        throw new IllegalStateException("Journal not found");
    }

    public static JournalInfo getJournal(AS400 as400, String schema, List<FileFilter> includes, Integer ccsid)
            throws IllegalStateException {
        if (includes.isEmpty()) {
            return getJournal(as400, schema);
        }
        try {
            final Set<JournalInfo> jis = new HashSet<>();
            for (final FileFilter f : includes) {
                if (!schema.equals(f.schema())) {
                    throw new IllegalArgumentException(
                            String.format("schema %s does not match for filter: %s", schema, f));
                }
                final JournalInfo ji = getJournal(as400, f.schema(), f.table(), ccsid);
                jis.add(ji);
            }
            if (jis.size() > 1) {
                throw new IllegalArgumentException(
                        String.format("more than one journal for the set of tables journals: %s", jis));
            }
            return jis.iterator().next();
        }
        catch (final Exception e) {
            throw new IllegalStateException("unable to retrieve journal details", e);
        }
    }

    public static JournalInfo getJournal(AS400 as400, String schema, String table, Integer ccsid) throws Exception {
        final int rcvLen = 32768;
        final String filename = padRight(table.toUpperCase(), 10) + padRight(schema.toUpperCase(), 10);

        final AS400Text AS400_TEXT_8;
        final AS400Text AS400_TEXT_20;
        final AS400Text AS400_TEXT_1;
        final AS400Text AS400_TEXT_10;

        if (ccsid > -1) {
            AS400_TEXT_8 = new AS400Text(8, ccsid.intValue(), as400);
            AS400_TEXT_20 = new AS400Text(20, ccsid.intValue(), as400);
            AS400_TEXT_1 = new AS400Text(1, ccsid.intValue(), as400);
            AS400_TEXT_10 = new AS400Text(10, ccsid.intValue(), as400);
        }
        else {
            AS400_TEXT_8 = new AS400Text(8, as400);
            AS400_TEXT_20 = new AS400Text(20, as400);
            AS400_TEXT_1 = new AS400Text(1, as400);
            AS400_TEXT_10 = new AS400Text(10, as400);
        }

        final ProgramParameter[] parameters = new ProgramParameter[]{
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, rcvLen), // 1
                                                                                  // Receiver
                                                                                  // variable
                                                                                  // (output)
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, AS400_BIN4.toBytes(rcvLen)), // 2
                                                                                                      // Length
                                                                                                      // of
                                                                                                      // receiver
                                                                                                      // variable
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, 20), // 3
                                                                              // Qualified
                                                                              // returned
                                                                              // file
                                                                              // name
                                                                              // (output)
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, AS400_TEXT_8.toBytes("FILD0100")), // 4
                                                                                                            // Format
                                                                                                            // name
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, AS400_TEXT_20.toBytes(filename)), // 5
                                                                                                           // Qualified
                                                                                                           // file
                                                                                                           // name
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, AS400_TEXT_10.toBytes("*FIRST")), // 6
                                                                                                           // Record
                                                                                                           // format
                                                                                                           // name
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, AS400_TEXT_1.toBytes("0")), // 7
                                                                                                     // Override
                                                                                                     // processing
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, AS400_TEXT_10.toBytes("*LCL")), // 8
                                                                                                         // System
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, AS400_TEXT_10.toBytes("*INT")), // 9
                                                                                                         // Format
                                                                                                         // type
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, AS400_BIN4.toBytes(0)), // 10
                                                                                                 // Error
                                                                                                 // Code
        };

        final ProgramCall pc = new ProgramCall();
        pc.setSystem(as400);
        final QSYSObjectPathName programName = new QSYSObjectPathName("QSYS", "QDBRTVFD", "PGM");
        pc.setProgram(programName.getPath(), parameters);
        final boolean success = pc.run();
        if (success) {
            final byte[] data = parameters[0].getOutputData();
            final int offsetJournalHeader = decodeInt(data, 378);
            int offsetJournalOrn = decodeInt(data, offsetJournalHeader + 378);
            offsetJournalOrn += offsetJournalHeader;

            final String journalName = decodeString(data, offsetJournalOrn, 10);
            final String journalLib = decodeString(data, offsetJournalOrn + 10, 10);
            return new JournalInfo(journalName, journalLib);
        }
        else {
            final String msg = Arrays.asList(pc.getMessageList()).stream().map(AS400Message::getText).reduce("",
                    (a, s) -> a + s);
            throw new IllegalStateException(String.format("Journal not found for %s.%s error %s", schema, table, msg));
        }
    }

    public static class JournalRetrievalCriteria {
        private static final AS400Text AS400_TEXT_1 = new AS400Text(1);
        private static final Integer ZERO_INT = Integer.valueOf(0);
        private static final Integer TWELVE_INT = Integer.valueOf(12);
        private static final Integer ONE_INT = Integer.valueOf(1);
        private final ArrayList<AS400DataType> structure = new ArrayList<>();
        private final ArrayList<Object> data = new ArrayList<>();

        public JournalRetrievalCriteria() {
            // first element is the number of variable length records
            structure.add(AS400_BIN4);
            structure.add(AS400_BIN4);
            structure.add(AS400_BIN4);
            structure.add(AS400_BIN4);
            structure.add(AS400_TEXT_1);
            data.add(ONE_INT); // number of records
            data.add(TWELVE_INT); // data length
            data.add(ONE_INT); // 1 = journal directory info
            data.add(ZERO_INT);
            data.add("");
        }

        public AS400DataType[] getStructure() {
            return structure.toArray(new AS400DataType[structure.size()]);
        }

        public Object[] getObject() {
            return data.toArray(new Object[0]);
        }
    }

    /**
     * uses the current attached journal information in the header
     *
     * @see https://www.ibm.com/support/knowledgecenter/ssw_ibm_i_74/apis/QJORJRNI.htm
     * @param as400
     * @param journalLibrary
     * @param journalFile
     * @return
     * @throws Exception
     */
    public static JournalReceiver getReceiver(AS400 as400, JournalInfo journalLib, Integer ccsid) throws Exception {
        final int rcvLen = 4096;
        final String jrnLib = padRight(journalLib.journalName(), 10) + padRight(journalLib.journalLibrary(), 10);
        final String format = "RJRN0200";

        final AS400Text AS400_TEXT_8;
        final AS400Text AS400_TEXT_20;

        if (ccsid > -1) {
            AS400_TEXT_8 = new AS400Text(8, ccsid.intValue(), as400);
            AS400_TEXT_20 = new AS400Text(20, ccsid.intValue(), as400);
        }
        else {
            AS400_TEXT_8 = new AS400Text(8, as400);
            AS400_TEXT_20 = new AS400Text(20, as400);
        }

        final ProgramParameter[] parameters = new ProgramParameter[]{
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, rcvLen),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, AS400_BIN4.toBytes(rcvLen / 4096)),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, AS400_TEXT_20.toBytes(jrnLib)),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, AS400_TEXT_8.toBytes(format)),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, EMPTY_AS400_TEXT),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, AS400_BIN4.toBytes(0)) };

        return callServiceProgram(as400, JOURNAL_SERVICE_LIB, "QjoRetrieveJournalInformation", parameters,
                (byte[] data) -> {
                    // Attached journal receiver name. The name of the journal
                    // receiver that is
                    // currently attached to this journal. This field will be
                    // blank if no journal
                    // receivers are attached.
                    final String journalReceiver = decodeString(data, 200, 10);
                    final String journalLibrary = decodeString(data, 210, 10);
                    return new JournalReceiver(journalReceiver, journalLibrary);
                });
    }

    private byte[] getReceiversForJournal(AS400 as400, JournalInfo journalLib, int bufSize, Integer ccsid) throws Exception {
        final String jrnLib = padRight(journalLib.journalName(), 10) + padRight(journalLib.journalLibrary(), 10);
        final String format = "RJRN0200";
        final AS400Text AS400_TEXT_8;
        final AS400Text AS400_TEXT_20;
        if (ccsid > -1) {
            AS400_TEXT_8 = new AS400Text(8, ccsid.intValue(), as400);
            AS400_TEXT_20 = new AS400Text(20, ccsid.intValue(), as400);
        }
        else {
            AS400_TEXT_8 = new AS400Text(8, as400);
            AS400_TEXT_20 = new AS400Text(20, as400);
        }

        final JournalRetrievalCriteria criteria = new JournalRetrievalCriteria();
        final byte[] toRetrieve = new AS400Structure(criteria.getStructure()).toBytes(criteria.getObject());
        final ProgramParameter[] parameters = new ProgramParameter[]{
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, bufSize),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, AS400_BIN4.toBytes(bufSize / 4096)),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, AS400_TEXT_20.toBytes(jrnLib)),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, AS400_TEXT_8.toBytes(format)),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, toRetrieve),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, AS400_BIN4.toBytes(0)) };
        return callServiceProgram(as400, JOURNAL_SERVICE_LIB, "QjoRetrieveJournalInformation", parameters,
                (byte[] data) -> data);
    }

    /**
     * requests the list of receivers and orders them in attach time
     *
     * @see https://www.ibm.com/support/knowledgecenter/ssw_ibm_i_74/apis/QJORJRNI.htm
     * @param as400
     * @param journalLibrary
     * @param journalFile
     * @return
     * @throws Exception
     */
    public List<DetailedJournalReceiver> getReceivers(AS400 as400, JournalInfo journalLib, Integer ccsid) throws Exception {
        final int defaultSize = 32768; // 4k takes 31ms 32k takes 85ms must be a
                                       // multiple of 4k and 0 not allowed

        byte[] data = getReceiversForJournal(as400, journalLib, defaultSize, ccsid);
        final int actualSizeRequired = decodeInt(data, 4) * 4096; // bytes
                                                                  // available -
                                                                  // value
                                                                  // returned
                                                                  // for
                                                                  // rjrn0200 is
                                                                  // 4k
        // pages
        if (actualSizeRequired > defaultSize) {
            data = getReceiversForJournal(as400, journalLib, actualSizeRequired + 4096, ccsid); // allow
            // for
            // any
            // growth
            // since
            // call
        }

        final Integer keyOffset = decodeInt(data, 8) + 4;
        final Integer totalKeys = decodeInt(data, keyOffset);

        final KeyDecoder keyDecoder = new KeyDecoder();

        final List<DetailedJournalReceiver> l = new ArrayList<>();

        for (int k = 0; k < totalKeys; k++) {
            final KeyHeader kheader = keyDecoder.decode(data, keyOffset + k * KEY_HEADER_LENGTH);
            if (kheader.getKey() == 1) {

                final ReceiverDecoder dec = new ReceiverDecoder();
                for (int i = 0; i < kheader.getNumberOfEntries(); i++) {
                    final int kioffset = keyOffset + kheader.getOffset() + kheader.getLengthOfHeader()
                            + i * kheader.getLengthOfKeyInfo();

                    final JournalReceiverInfo r = dec.decode(data, kioffset);
                    final DetailedJournalReceiver details = getReceiverDetails(as400, r, ccsid);

                    l.add(details);
                }
            }
        }
        cache.keepOnly(l);

        return DetailedJournalReceiver.lastJoined(l);
    }

    DetailedJournalReceiver getOffset(AS400 as400, JournalReceiver receiver, Integer ccsid) throws Exception {
        return getReceiverDetails(as400, new JournalReceiverInfo(receiver, null, null, Optional.empty()), ccsid);
    }

    /**
     * @see https://www.ibm.com/support/knowledgecenter/ssw_ibm_i_74/apis/QJORRCVI.htm
     * @param as400
     * @param receiverInfo
     * @return
     * @throws Exception
     */
    private DetailedJournalReceiver getReceiverDetails(AS400 as400, JournalReceiverInfo receiverInfo, Integer ccsid) throws Exception {
        final int rcvLen = 32768;
        final String receiverNameLib = padRight(receiverInfo.receiver().name(), 10)
                + padRight(receiverInfo.receiver().library(), 10);
        if (cache.containsKey(receiverInfo)) {
            final DetailedJournalReceiver r = cache.getUpdatingStatus(receiverInfo);
            if (!r.isAttached()) { // don't use attached journal cache
                return r;
            }
        }
        final AS400Text AS400_TEXT_8;
        final AS400Text AS400_TEXT_20;

        if (ccsid > -1) {
            AS400_TEXT_8 = new AS400Text(8, ccsid.intValue(), as400);
            AS400_TEXT_20 = new AS400Text(20, ccsid.intValue(), as400);
        }
        else {
            AS400_TEXT_8 = new AS400Text(8, as400);
            AS400_TEXT_20 = new AS400Text(20, as400);
        }

        final String format = "RRCV0100";
        final ProgramParameter[] parameters = new ProgramParameter[]{
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, rcvLen),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, AS400_BIN4.toBytes(rcvLen)),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, AS400_TEXT_20.toBytes(receiverNameLib)),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, AS400_TEXT_8.toBytes(format)),
                new ProgramParameter(ProgramParameter.PASS_BY_REFERENCE, AS400_BIN4.toBytes(0)) };

        return callServiceProgram(as400, JOURNAL_SERVICE_LIB, "QjoRtvJrnReceiverInformation", parameters,
                (byte[] data) -> {
                    final String journalName = decodeString(data, 8, 10);
                    final String attachTimeStr = decodeString(data, 95, 13);
                    final String nextReceiverName = decodeString(data, 332, 10);
                    String nextReceiverLib = decodeString(data, 342, 10);
                    nextReceiverLib = (nextReceiverLib == null) ? receiverInfo.receiver().library() : nextReceiverLib;
                    final Long numberOfEntries = Long.valueOf(decodeString(data, 372, 20));
                    final Long maxEntryLength = Long.valueOf(decodeString(data, 392, 20));
                    final BigInteger firstSequence = decodeBigIntFromString(data, 412, as400, ccsid);
                    final BigInteger lastSequence = decodeBigIntFromString(data, 432, as400, ccsid);
                    final JournalStatus status = JournalStatus.valueOfString(decodeString(data, 88, 1));

                    if (!journalName.equals(receiverInfo.receiver().name())) {
                        final String msg = String.format("journal names don't match requested %s got %s",
                                receiverInfo.receiver().name(), journalName);
                        throw new Exception(msg);
                    }
                    final Date attachTime = ReceiverDecoder.toDate(attachTimeStr);
                    final JournalReceiverInfo updatedInfo = new JournalReceiverInfo(receiverInfo.receiver(), attachTime,
                            status, receiverInfo.chain());

                    final Optional<JournalReceiver> nextReceiver = (nextReceiverName == null) ? Optional.empty()
                            : Optional.of(new JournalReceiver(nextReceiverName, nextReceiverLib));

                    final DetailedJournalReceiver dr = new DetailedJournalReceiver(updatedInfo, firstSequence,
                            lastSequence, nextReceiver, maxEntryLength, numberOfEntries);

                    cache.put(dr);
                    return dr;
                });
    }

    /**
     *
     * @param <T>
     *            return type of processor
     * @param as400
     * @param programLibrary
     * @param program
     * @param parameters
     *            assumes first parameter is output
     * @param processor
     * @return output of processor
     * @throws Exception
     */
    public static <T> T callServiceProgramParams(AS400 as400, String programLibrary, String program,
                                                 ProgramParameter[] parameters, ProcessDataAllParamaterData<T> processor)
            throws Exception {
        final ServiceProgramCall spc = new ServiceProgramCall(as400);

        spc.getServerJob().setLoggingLevel(0);
        spc.setProgram(programLibrary, parameters);
        spc.setProcedureName(program);
        spc.setAlignOn16Bytes(true);
        spc.setReturnValueFormat(ServiceProgramCall.RETURN_INTEGER);
        final boolean success = spc.run();
        if (success) {
            return processor.process(parameters);
        }
        else {
            final String msg = Arrays.asList(spc.getMessageList()).stream().map(AS400Message::getText).reduce("",
                    (a, s) -> a + s);
            log.error(String.format("service program %s/%s call failed %s", programLibrary, program, msg));
            throw new Exception(msg);
        }
    }

    /**
     *
     * @param <T>
     *            return type of processor
     * @param as400
     * @param programLibrary
     * @param program
     * @param parameters
     *            assumes first parameter is output
     * @param processor
     * @return output of processor
     * @throws Exception
     */
    public static <T> T callServiceProgram(AS400 as400, String programLibrary, String program,
                                           ProgramParameter[] parameters, ProcessFirstParamaterData<T> processor)
            throws Exception {
        return callServiceProgramParams(as400, programLibrary, program, parameters,
                p -> processor.process(p[0].getOutputData()));
    }

    public static Integer decodeInt(byte[] data, int offset) {
        final byte[] b = Arrays.copyOfRange(data, offset, offset + 4);
        return Integer.valueOf(AS400_BIN4.toInt(b));
    }

    public static Long decodeLong(byte[] data, int offset) {
        final byte[] b = Arrays.copyOfRange(data, offset, offset + 8);
        return Long.valueOf(AS400_BIN8.toLong(b));
    }

    public static String decodeString(byte[] data, int offset, int length) {
        final byte[] b = Arrays.copyOfRange(data, offset, offset + length);
        return StringHelpers.safeTrim((String) new AS400Text(length).toObject(b));
    }

    public static String padRight(String s, int n) {
        return String.format("%1$-" + n + "s", s);
    }

    public interface ProcessDataAllParamaterData<T> {
        T process(ProgramParameter[] parameters) throws Exception;
    }

    public interface ProcessFirstParamaterData<T> {
        T process(byte[] data) throws Exception;
    }

    public static BigInteger decodeBigIntFromString(byte[] data, int offset, AS400 as400, Integer ccsid) {
        final byte[] b = Arrays.copyOfRange(data, offset, offset + 20);
        final AS400Text AS400_TEXT_20;

        if (ccsid > -1) {
            AS400_TEXT_20 = new AS400Text(20, ccsid.intValue(), as400);
        }
        else {
            AS400_TEXT_20 = new AS400Text(20, as400);
        }

        final String s = (String) AS400_TEXT_20.toObject(b);
        return new BigInteger(s);
    }
}
