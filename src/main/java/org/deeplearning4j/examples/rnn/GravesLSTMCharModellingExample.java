package org.deeplearning4j.examples.rnn;

import org.apache.commons.io.FileUtils;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.storage.StorageLevel;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.BackpropType;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.Updater;
import org.deeplearning4j.nn.conf.layers.GravesLSTM;
import org.deeplearning4j.nn.conf.layers.RnnOutputLayer;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.spark.impl.multilayer.SparkDl4jMultiLayer;
import org.deeplearning4j.spark.impl.paramavg.ParameterAveragingTrainingMaster;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.lossfunctions.LossFunctions.LossFunction;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.*;

/**GravesLSTM + Spark character modelling example
 * Example: Train a LSTM RNN to generates text, one character at a time.
 * Training here is done on Spark (local)
 * This example is somewhat inspired by Andrej Karpathy's blog post,
 * "The Unreasonable Effectiveness of Recurrent Neural Networks"
 * http://karpathy.github.io/2015/05/21/rnn-effectiveness/
 * This example is set up to train on the Complete Works of William Shakespeare, downloaded
 * from Project Gutenberg. Training on other text sources should be relatively easy to implement.
 *
 * @author Alex Black
 */
public class GravesLSTMCharModellingExample {

    public static Map<Integer, Character> INT_TO_CHAR = getIntToChar();
    public static Map<Character, Integer> CHAR_TO_INT = getCharToInt();
    public static final int N_CHARS = INT_TO_CHAR.size();
    public static int nIn = CHAR_TO_INT.size();
    public static int nOut = CHAR_TO_INT.size();

    //First: Set up network configuration, and some setting specific to this example
    public static int sequenceLength = 1000;                      //Length of each sequence (used in truncated BPTT)
    public static int truncatedBPTTLength = 100;                  //Configuration for truncated BPTT. See http://deeplearning4j.org/usingrnns.html for details
    public static int sampleCharsEveryNAveragings = 10;           //How frequently should we generate samples from the network?
    public static int lstmLayerSize = 200;                        //Number of units in each GravesLSTM layer
    public static int numEpochs = 5;                              //Total number of training + sample generation epochs
    public static int nSamplesToGenerate = 4;                     //Number of samples to generate after each training epoch
    public static int nCharactersToSample = 300;                  //Length of each sample to generate
    public static String generationInitialization = null;         //Optional character initialization; a random character is used if null
    // Above is Used to 'prime' the LSTM with a character sequence to continue/complete.
    // Initialization characters must all be in CharacterIterator.getMinimalCharacterSet() by default

    public static void main(String[] args) throws Exception {
        Random rng = new Random(12345);

        //Set up network configuration:
        MultiLayerNetwork net = new MultiLayerNetwork(getConfiguration());
        net.init();


        //-------------------------------------------------------------
        //Second: Set up the Spark-specific configuration
        int examplesPerWorker = 8;      //How many examples should be used per worker (executor) when fitting?
        /* How frequently should we average parameters (in number of minibatches)?
        Averaging too frequently can be slow (synchronization + serialization costs) whereas too infrequently can result
        learning difficulties (i.e., network may not converge) */
        int averagingFrequency = 3;

        //Set up Spark configuration and context
        SparkConf sparkConf = new SparkConf();
        sparkConf.setMaster("local[*]");
        sparkConf.setAppName("LSTM_Char");
        JavaSparkContext sc = new JavaSparkContext(sparkConf);

        //Get data. For the sake of this example, we are doing the following operations:
        // File -> String -> List<String> (split into length "sequenceLength" characters) -> JavaRDD<String> -> JavaRDD<DataSet>
        List<String> list = getShakespeareAsList(sequenceLength);
        JavaRDD<String> rawStrings = sc.parallelize(list);
        rawStrings.persist(StorageLevel.MEMORY_ONLY());
        final Broadcast<Map<Character, Integer>> bcCharToInt = sc.broadcast(CHAR_TO_INT);

        //Set up the TrainingMaster. The TrainingMaster controls how learning is actually executed on Spark
        //Here, we are using standard parameter averaging
        int examplesPerDataSetObject = 1;
        ParameterAveragingTrainingMaster tm = new ParameterAveragingTrainingMaster.Builder(examplesPerDataSetObject)
                .workerPrefetchNumBatches(2)    //Asynchronously prefetch up to 2 batches
                .saveUpdater(true)
                .averagingFrequency(averagingFrequency)
                .batchSizePerWorker(examplesPerWorker)
                .build();
        SparkDl4jMultiLayer sparkNetwork = new SparkDl4jMultiLayer(sc, net, tm);

        //Do training, and then generate and print samples from network
        for (int i = 0; i < numEpochs; i++) {

            //Split the Strings and convert to data
            //Note that the usual approach would be to use SparkDl4jMultiLayer.fit(JavaRDD<DataSet>) method instead of manually
            // splitting like we do here, but we want to periodically generate samples from the network (which we can't do if we
            // simply call fit(JavaRDD<DataSet>)
            JavaRDD<String>[] stringsSplit = splitStrings(rawStrings, sc.defaultParallelism() * examplesPerWorker * averagingFrequency);

            int iter = 0;
            for (JavaRDD<String> stringSplit : stringsSplit) {
                JavaRDD<DataSet> data = stringSplit.map(new StringToDataSetFn(bcCharToInt));
                net = sparkNetwork.fit(data);

                System.out.println("Score after averaging #" + iter++ + ": " + sparkNetwork.getScore());

                if(iter % sampleCharsEveryNAveragings == 0){
                    System.out.println("--------------------");
                    System.out.println("Sampling characters from network given initialization \"" +
                            (generationInitialization == null ? "" : generationInitialization) + "\"");
                    String[] samples = sampleCharactersFromNetwork(generationInitialization, net, rng, INT_TO_CHAR,
                            nCharactersToSample, nSamplesToGenerate);
                    for (int j = 0; j < samples.length; j++) {
                        System.out.println("----- Sample " + j + " -----");
                        System.out.println(samples[j]);
                        System.out.println();
                    }
                }
            }
        }

        System.out.println("\n\nExample complete");
    }

    public static MultiLayerConfiguration getConfiguration(){
        //Set up network configuration:
        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
                .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT).iterations(1)
                .learningRate(0.1)
                .rmsDecay(0.95)
                .seed(12345)
                .weightInit(WeightInit.XAVIER)
                .activation("tanh")
                .updater(Updater.RMSPROP)
                .regularization(true)
                .l2(0.001)
                .list()
                .layer(0, new GravesLSTM.Builder().nIn(nIn).nOut(lstmLayerSize).build())
                .layer(1, new GravesLSTM.Builder().nIn(lstmLayerSize).nOut(lstmLayerSize).build())
                .layer(2, new RnnOutputLayer.Builder(LossFunction.MCXENT).activation("softmax")        //MCXENT + softmax for classification
                        .nIn(lstmLayerSize).nOut(nOut).build())
                .pretrain(false).backprop(true)
                .backpropType(BackpropType.TruncatedBPTT).tBPTTForwardLength(truncatedBPTTLength).tBPTTBackwardLength(truncatedBPTTLength)
                .build();
        return conf;
    }

    private static JavaRDD<String>[] splitStrings(JavaRDD<String> in, int examplesPerSplit) {
        int nSplits;
        long nExamples = in.count();
        if (nExamples % examplesPerSplit == 0) {
            nSplits = (int) (nExamples / examplesPerSplit);
        } else {
            nSplits = (int) (nExamples / examplesPerSplit) + 1;
        }
        double[] splitWeights = new double[nSplits];
        for (int i = 0; i < nSplits; i++) splitWeights[i] = 1.0 / nSplits;
        return in.randomSplit(splitWeights);
    }


    public static class StringToDataSetFn implements Function<String, DataSet> {
        private final Broadcast<Map<Character, Integer>> ctiBroadcast;

        public StringToDataSetFn(Broadcast<Map<Character, Integer>> characterIntegerMap) {
            this.ctiBroadcast = characterIntegerMap;
        }

        @Override
        public DataSet call(String s) throws Exception {
            Map<Character, Integer> cti = ctiBroadcast.getValue();
            int length = s.length();
            INDArray features = Nd4j.zeros(1, N_CHARS, length - 1);
            INDArray labels = Nd4j.zeros(1, N_CHARS, length - 1);
            char[] chars = s.toCharArray();
            int[] f = new int[3];
            int[] l = new int[3];
            for (int i = 0; i < chars.length - 2; i++) {
                f[1] = cti.get(chars[i]);
                f[2] = i;
                l[1] = cti.get(chars[i + 1]);
                l[2] = i;

                features.putScalar(f, 1.0);
                labels.putScalar(l, 1.0);
            }
            return new DataSet(features, labels);
        }
    }

    public static List<String> getShakespeareAsList(int sequenceLength) throws IOException {
        //The Complete Works of William Shakespeare
        //5.3MB file in UTF-8 Encoding, ~5.4 million characters
        //https://www.gutenberg.org/ebooks/100
        String url = "https://s3.amazonaws.com/dl4j-distribution/pg100.txt";
        String tempDir = System.getProperty("java.io.tmpdir");
        String fileLocation = tempDir + "/Shakespeare.txt";    //Storage location from downloaded file
        File f = new File(fileLocation);
        if (!f.exists()) {
            FileUtils.copyURLToFile(new URL(url), f);
            System.out.println("File downloaded to " + f.getAbsolutePath());
        } else {
            System.out.println("Using existing text file at " + f.getAbsolutePath());
        }

        if (!f.exists()) throw new IOException("File does not exist: " + fileLocation);    //Download problem?

        String allData = getDataAsString(fileLocation);

        List<String> list = new ArrayList<>();
        int length = allData.length();
        int currIdx = 0;
        while (currIdx + sequenceLength < length) {
            int end = currIdx + sequenceLength;
            String substr = allData.substring(currIdx, end);
            currIdx = end;
            list.add(substr);
        }
        return list;
    }

    /**
     * Load data from a file, and remove any invalid characters.
     * Data is returned as a single large String
     */
    private static String getDataAsString(String filePath) throws IOException {
        List<String> lines = Files.readAllLines(new File(filePath).toPath(), Charset.defaultCharset());
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            char[] chars = line.toCharArray();
            for (int i = 0; i < chars.length; i++) {
                if (CHAR_TO_INT.containsKey(chars[i])) sb.append(chars[i]);
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Generate a sample from the network, given an (optional, possibly null) initialization. Initialization
     * can be used to 'prime' the RNN with a sequence you want to extend/continue.<br>
     * Note that the initalization is used for all samples
     *
     * @param initialization     String, may be null. If null, select a random character as initialization for all samples
     * @param charactersToSample Number of characters to sample from network (excluding initialization)
     * @param net                MultiLayerNetwork with one or more GravesLSTM/RNN layers and a softmax output layer
     */
    private static String[] sampleCharactersFromNetwork(String initialization, MultiLayerNetwork net, Random rng,
                                                        Map<Integer, Character> intToChar, int charactersToSample, int numSamples) {
        //Set up initialization. If no initialization: use a random character
        if (initialization == null) {
            int randomCharIdx = rng.nextInt(intToChar.size());
            initialization = String.valueOf(intToChar.get(randomCharIdx));
        }

        //Create input for initialization
        INDArray initializationInput = Nd4j.zeros(numSamples, intToChar.size(), initialization.length());
        char[] init = initialization.toCharArray();
        for (int i = 0; i < init.length; i++) {
            int idx = CHAR_TO_INT.get(init[i]);
            for (int j = 0; j < numSamples; j++) {
                initializationInput.putScalar(new int[]{j, idx, i}, 1.0f);
            }
        }

        StringBuilder[] sb = new StringBuilder[numSamples];
        for (int i = 0; i < numSamples; i++) sb[i] = new StringBuilder(initialization);

        //Sample from network (and feed samples back into input) one character at a time (for all samples)
        //Sampling is done in parallel here
        net.rnnClearPreviousState();
        INDArray output = net.rnnTimeStep(initializationInput);
        output = output.tensorAlongDimension(output.size(2) - 1, 1, 0);    //Gets the last time step output

        for (int i = 0; i < charactersToSample; i++) {
            //Set up next input (single time step) by sampling from previous output
            INDArray nextInput = Nd4j.zeros(numSamples, intToChar.size());
            //Output is a probability distribution. Sample from this for each example we want to generate, and add it to the new input
            for (int s = 0; s < numSamples; s++) {
                double[] outputProbDistribution = new double[intToChar.size()];
                for (int j = 0; j < outputProbDistribution.length; j++)
                    outputProbDistribution[j] = output.getDouble(s, j);
                int sampledCharacterIdx = sampleFromDistribution(outputProbDistribution, rng);

                nextInput.putScalar(new int[]{s, sampledCharacterIdx}, 1.0f);        //Prepare next time step input
                sb[s].append(intToChar.get(sampledCharacterIdx));    //Add sampled character to StringBuilder (human readable output)
            }

            output = net.rnnTimeStep(nextInput);    //Do one time step of forward pass
        }

        String[] out = new String[numSamples];
        for (int i = 0; i < numSamples; i++) out[i] = sb[i].toString();
        return out;
    }

    /**
     * Given a probability distribution over discrete classes, sample from the distribution
     * and return the generated class index.
     *
     * @param distribution Probability distribution over classes. Must sum to 1.0
     */
    private static int sampleFromDistribution(double[] distribution, Random rng) {
        double d = rng.nextDouble();
        double sum = 0.0;
        for (int i = 0; i < distribution.length; i++) {
            sum += distribution[i];
            if (d <= sum) return i;
        }
        //Should never happen if distribution is a valid probability distribution
        throw new IllegalArgumentException("Distribution is invalid? d=" + d + ", sum=" + sum);
    }

    /**
     * A minimal character set, with a-z, A-Z, 0-9 and common punctuation etc
     */
    private static char[] getValidCharacters() {
        List<Character> validChars = new LinkedList<>();
        for (char c = 'a'; c <= 'z'; c++) validChars.add(c);
        for (char c = 'A'; c <= 'Z'; c++) validChars.add(c);
        for (char c = '0'; c <= '9'; c++) validChars.add(c);
        char[] temp = {'!', '&', '(', ')', '?', '-', '\'', '"', ',', '.', ':', ';', ' ', '\n', '\t'};
        for (char c : temp) validChars.add(c);
        char[] out = new char[validChars.size()];
        int i = 0;
        for (Character c : validChars) out[i++] = c;
        return out;
    }

    private static Map<Integer, Character> getIntToChar() {
        Map<Integer, Character> map = new HashMap<>();
        char[] chars = getValidCharacters();
        for (int i = 0; i < chars.length; i++) {
            map.put(i, chars[i]);
        }
        return map;
    }

    private static Map<Character, Integer> getCharToInt() {
        Map<Character, Integer> map = new HashMap<>();
        char[] chars = getValidCharacters();
        for (int i = 0; i < chars.length; i++) {
            map.put(chars[i], i);
        }
        return map;
    }
}