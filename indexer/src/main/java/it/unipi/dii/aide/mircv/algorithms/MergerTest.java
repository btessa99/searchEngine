package it.unipi.dii.aide.mircv.algorithms;

import it.unipi.dii.aide.mircv.common.beans.*;
import it.unipi.dii.aide.mircv.common.config.ConfigurationParameters;
import it.unipi.dii.aide.mircv.common.utils.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.CharBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;


class MergerTest {

    private static final String TEST_DIRECTORY = ConfigurationParameters.getTestDir();
    private static final String PATH_TO_PARTIAL_VOCABULARY = TEST_DIRECTORY+"/partial_vocabulary/partial_vocabulary_";
    private static final String PATH_TO_PARTIAL_FREQUENCIES = TEST_DIRECTORY+"/partial_freqs/partial_freqs_";
    private static final String PATH_TO_PARTIAL_INDEXES_DOCS = TEST_DIRECTORY+"/partial_docids/partial_docids_";
    private static final String DOCINDEX_PATH = TEST_DIRECTORY+"/docIndex";
    private static final String VOCABULARY_PATH = TEST_DIRECTORY+"/vocabulary";
    private static final String INVERTED_INDEX_DOCIDS = TEST_DIRECTORY+"/docids";
    private static final String INVERTED_INDEX_FREQS = TEST_DIRECTORY+"/freqs";


    @BeforeEach
    void setUp() {
        System.out.println(TEST_DIRECTORY);
        FileUtils.deleteDirectory(TEST_DIRECTORY);
        FileUtils.createDirectory(TEST_DIRECTORY);
    }

    @AfterEach
    void tearDown() {
        FileUtils.deleteDirectory(TEST_DIRECTORY);
    }
    private static boolean writeIntermediateIndexesToDisk(ArrayList<ArrayList<PostingList>> intermediateIndexes) {
        for (ArrayList<PostingList> intermediateIndex : intermediateIndexes) {

            int i = intermediateIndexes.indexOf(intermediateIndex);

            try (
                    FileChannel docsFchan = (FileChannel) Files.newByteChannel(Paths.get(PATH_TO_PARTIAL_INDEXES_DOCS + i),
                            StandardOpenOption.WRITE,
                            StandardOpenOption.READ,
                            StandardOpenOption.CREATE
                    );
                    FileChannel freqsFchan = (FileChannel) Files.newByteChannel(Paths.get(PATH_TO_PARTIAL_FREQUENCIES + i),
                            StandardOpenOption.WRITE,
                            StandardOpenOption.READ,
                            StandardOpenOption.CREATE);
                    FileChannel vocabularyFchan = (FileChannel) Files.newByteChannel(Paths.get(PATH_TO_PARTIAL_VOCABULARY + i),
                            StandardOpenOption.WRITE,
                            StandardOpenOption.READ,
                            StandardOpenOption.CREATE)
            ) {
                long vocOffset = 0;
                long docidOffset = 0;
                long freqOffset = 0;
                for (PostingList postingList : intermediateIndex) {
                    int numPostings = intermediateIndex.size();
                    // instantiation of MappedByteBuffer for integer list of docids and for integer list of freqs
                    MappedByteBuffer docsBuffer = docsFchan.map(FileChannel.MapMode.READ_WRITE, docidOffset, numPostings * 4L);
                    MappedByteBuffer freqsBuffer = freqsFchan.map(FileChannel.MapMode.READ_WRITE, freqOffset, numPostings * 4L);

                    // check if MappedByteBuffers are correctly instantiated
                    if (docsBuffer != null && freqsBuffer != null) {
                        //create vocabulary entry
                        VocabularyEntry vocEntry = new VocabularyEntry(postingList.getTerm());
                        vocEntry.setMemoryOffset(docsBuffer.position());
                        vocEntry.setFrequencyOffset(docsBuffer.position());

                        int MaxDL = 0;

                        // write postings to file
                        for (Posting posting : postingList.getPostings()) {
                            // encode docid and freq
                            docsBuffer.putInt(posting.getDocid());
                            freqsBuffer.putInt(posting.getFrequency());

                            // TODO: update

                            int dl = DocumentIndex.getInstance().getLength(posting.getDocid());
                            if(dl>MaxDL)
                                MaxDL = dl;

                        }

                        vocEntry.updateStatistics(postingList);
                        vocEntry.setDocidSize(numPostings * 4);
                        vocEntry.setFrequencySize(numPostings * 4);
                        vocEntry.setMemoryOffset(docidOffset);
                        vocEntry.setFrequencyOffset(freqOffset);
                        //vocEntry.setMaxDl(MaxDL);

                        vocOffset = vocEntry.writeEntryToDisk(vocOffset, vocabularyFchan);

                        docidOffset += numPostings * 4L;
                        freqOffset += numPostings * 4L;

                    } else {
                        return false;
                    }
                }
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    private static ArrayList<ArrayList<Posting>> retrieveIndexFromDisk(){
        // get vocabulary from disk
        Vocabulary v = Vocabulary.getInstance();
        v.readFromDisk();

        ArrayList<ArrayList<Posting>> mergedLists = new ArrayList<>(v.size());

        ArrayList<VocabularyEntry> vocEntries = new ArrayList<>();
        vocEntries.addAll(v.values());

        for(VocabularyEntry vocabularyEntry: vocEntries){
            PostingList p = new PostingList();
            p.setTerm(vocabularyEntry.getTerm());
            p.openList();
            ArrayList<Posting> postings = new ArrayList<>();

            while(p.next()!=null)
                postings.add(p.getCurrentPosting());

            p.closeList();

            mergedLists.add(postings);
        }
        return mergedLists;
    }



    private static LinkedHashMap<Integer, DocumentIndexEntry> buildDocIndex(ArrayList<ArrayList<PostingList>> indexes){
        LinkedHashMap<Integer, DocumentIndexEntry> docIndex = new LinkedHashMap<>();
        int docCounter = 0;

        for(ArrayList<PostingList> index: indexes){
            for(PostingList postingList: index){
                for(Posting posting: postingList.getPostings()){
                    DocumentIndexEntry docEntry = docIndex.get(posting.getDocid());
                    if(docEntry!=null){
                        docEntry.setDocLen(docEntry.getDocLen()+posting.getFrequency());
                    } else {
                        docEntry = new DocumentIndexEntry(Integer.toString(posting.getDocid()), docCounter, posting.getFrequency());
                        docIndex.put(posting.getDocid(), docEntry);
                        docCounter++;
                    }
                }
            }

        }
        return docIndex;
    }

    public static boolean writeDocumentIndexToDisk(LinkedHashMap<Integer, DocumentIndexEntry> docIndex) {

        // try to open a file channel to the file of the inverted index
        try (FileChannel fChan = (FileChannel) Files.newByteChannel(
                Paths.get(DOCINDEX_PATH),
                StandardOpenOption.WRITE,
                StandardOpenOption.READ,
                StandardOpenOption.CREATE))
        {
            int memOffset = 0;
            for(DocumentIndexEntry documentIndexEntry: docIndex.values()){
                // instantiation of MappedByteBuffer for the entry
                MappedByteBuffer buffer = fChan.map(FileChannel.MapMode.READ_WRITE, memOffset, DocumentIndexEntry.ENTRY_SIZE);

                // Buffer not created
                if(buffer == null)
                    return false;

                // Create the CharBuffer with size = PID_SIZE
                CharBuffer charBuffer = CharBuffer.allocate(DocumentIndexEntry.PID_SIZE);
                for(int i = 0; i < documentIndexEntry.getPid().length(); i++)
                    charBuffer.put(i, documentIndexEntry.getPid().charAt(i));

                // Write the PID into file
                buffer.put(StandardCharsets.UTF_8.encode(charBuffer));

                // Write the docid into file
                buffer.putInt(documentIndexEntry.getDocid());
                // Write the doclen into file
                buffer.putInt(documentIndexEntry.getDocLen());

                // update memory offset
                memOffset += DocumentIndexEntry.ENTRY_SIZE;
            }

        } catch(Exception e){
            e.printStackTrace();
            return false;
        }

        //CollectionSize.updateCollectionSize(docIndex.size());
        return true;
    }


    public void test1(Boolean compressionMode){

        // building partial index 1
        ArrayList<PostingList> index1 = new ArrayList<>();

        index1.add(new PostingList("alberobello\t0:3 1:3: 3:7"));
        index1.add(new PostingList("newyork\t0:5 2:2: 3:6"));
        index1.add(new PostingList("pisa\t0:1 4:3"));

        // insert partial index to array of partial indexes
        ArrayList<ArrayList<PostingList>> intermediateIndexes = new ArrayList<>();
        intermediateIndexes.add(index1);

        // build document index for intermediate indexes
        LinkedHashMap<Integer, DocumentIndexEntry> docIndex = buildDocIndex(intermediateIndexes);

        // write document index to disk
        assertTrue(writeDocumentIndexToDisk(docIndex), "Error while writing document index to disk");

        // write intermediate indexes to disk so that SPIMI can be executed
        assertTrue(writeIntermediateIndexesToDisk(intermediateIndexes), "Error while writing intermediate indexes to disk");

        // merging intermediate indexes
        assertTrue(Merger.mergeIndexes(intermediateIndexes.size(), compressionMode, true), "Error: merging failed");


        ArrayList<ArrayList<Posting>> mergedLists = retrieveIndexFromDisk();

        // build expected results
        ArrayList<ArrayList<Posting>> expectedResults = new ArrayList<>(3);

        ArrayList<Posting> postings = new ArrayList<>();
        postings.addAll(List.of(new Posting[]{
                new Posting(0, 3),
                new Posting(1, 4),
                new Posting(2,7)
        }));
        expectedResults.add(postings);
        postings = new ArrayList<>();
        postings.addAll(List.of(new Posting[]{
                new Posting(0,5),
                new Posting(2,2),
                new Posting(3,6)
        }));
        expectedResults.add(postings);

        postings = new ArrayList<>();
        postings.addAll(List.of(new Posting[]{
                new Posting(1,1),
                new Posting(4,3)
        }));
        expectedResults.add(postings);

        assertEquals(mergedLists.toString(), expectedResults.toString(), "Error, expected results are different from actual results.");
    }

    @Test
    void testMergeIndexes() {
        test1(false);
        test1(true);
    }

}