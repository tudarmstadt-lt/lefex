package de.tudarmstadt.lt.jst.ExtractTermFeatureScores;

import java.net.URI;
import java.util.Arrays;
import de.tudarmstadt.lt.jst.Utils.MultiOutputIntSumReducer;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.LazyOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.mapreduce.lib.reduce.IntSumReducer;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;


public class HadoopMain extends Configured implements Tool {

	private boolean runJob(String inDir, String outDir) throws Exception {
		Configuration conf = getConf();
		conf.setBoolean("mapred.output.compress", false);
		conf.set("mapred.output.compression.codec", "org.apache.hadoop.io.compress.GzipCodec");
		Job job = Job.getInstance(conf);
		job.setJarByClass(HadoopMain.class);
		FileInputFormat.addInputPath(job, new Path(inDir));
		FileOutputFormat.setOutputPath(job, new Path(outDir));
		job.setMapperClass(HadoopMap.class);
		job.setMapOutputKeyClass(Text.class);
		job.setMapOutputValueClass(IntWritable.class);
		job.setOutputKeyClass(Text.class);
		job.setOutputValueClass(IntWritable.class);
		job.setCombinerClass(IntSumReducer.class);
		job.setReducerClass(MultiOutputIntSumReducer.class);

		// Turn off the default output ("part-..."), we don't need it
		LazyOutputFormat.setOutputFormatClass(job, TextOutputFormat.class);
		MultipleOutputs.addNamedOutput(job, "W", TextOutputFormat.class, Text.class, IntWritable.class);
		MultipleOutputs.addNamedOutput(job, "CoocF", TextOutputFormat.class, Text.class, IntWritable.class);
		MultipleOutputs.addNamedOutput(job, "CoocWF", TextOutputFormat.class, Text.class, IntWritable.class);
		MultipleOutputs.addNamedOutput(job, "F", TextOutputFormat.class, Text.class, IntWritable.class);
		MultipleOutputs.addNamedOutput(job, "WF", TextOutputFormat.class, Text.class, IntWritable.class);

		String[] mwePaths = conf.getStrings("holing.mwe.vocabulary", "");
		String mwePath = "";
		if (mwePaths != null && mwePaths.length > 0 && mwePaths[0] != null) mwePath = mwePaths[0];
		if (!mwePath.equals("")) job.addCacheFile(new URI(mwePath + "#mwe_voc"));

		job.setJobName("JoSimText - Feature Extraction");
		return job.waitForCompletion(true);
	}

	@Override
	public int run(String[] args) throws Exception {
		System.out.println("args:" + Arrays.asList(args));
		if (args.length != 2) {
			System.out.println("Usage: <input-path-to-corpus> <output-path-to-features>");
			System.exit(1);
		}
		String inDir = args[0];
		String outDir = args[1];
		boolean success = runJob(inDir, outDir);
		return success ? 0 : 1;
	}

	public static void main(final String[] args) throws Exception {
		Configuration conf = new Configuration();
        int res = ToolRunner.run(conf, new HadoopMain(), args);
		System.exit(res);
	}
}