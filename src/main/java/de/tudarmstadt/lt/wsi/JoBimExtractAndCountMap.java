package de.tudarmstadt.lt.wsi;

import static org.apache.uima.fit.factory.TypeSystemDescriptionFactory.createTypeSystemDescription;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.log4j.Logger;
import org.apache.uima.analysis_engine.AnalysisEngine;
import org.apache.uima.cas.CASException;
import org.apache.uima.fit.factory.AnalysisEngineFactory;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.util.CasCreationUtils;

import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token;
import de.tudarmstadt.ukp.dkpro.core.api.syntax.type.dependency.Dependency;
import de.tudarmstadt.ukp.dkpro.core.maltparser.MaltParser;
import de.tudarmstadt.ukp.dkpro.core.opennlp.OpenNlpPosTagger;
import de.tudarmstadt.ukp.dkpro.core.opennlp.OpenNlpSegmenter;


class JoBimExtractAndCountMap extends Mapper<LongWritable, Text, Text, IntWritable> {
	Logger log = Logger.getLogger("de.tudarmstadt.lt.wsi");
	AnalysisEngine segmenter;
	AnalysisEngine posTagger;
	AnalysisEngine lemmatizer;
	AnalysisEngine depParser;
	JCas jCas;
	boolean semantifyDependencies;
	boolean computeDependencies;
	boolean computeCoocs;
	boolean generatePseudoSenses;
	Set<String> generatePseudoSensesWords;
	int generatePseudoSensesNum;
	int maxSentenceLength;
	
	private static IntWritable ONE = new IntWritable(1);
	
	@Override
	public void setup(Context context) {
		log.info("Initializing JoBimExtractAndCount...");
		computeCoocs = context.getConfiguration().getBoolean("holing.coocs", false);
		maxSentenceLength = context.getConfiguration().getInt("holing.sentences.maxlength", 100);
		computeDependencies = context.getConfiguration().getBoolean("holing.dependencies", false);
		semantifyDependencies = context.getConfiguration().getBoolean("holing.dependencies.semantify", false);
		try {
			segmenter = AnalysisEngineFactory.createEngine(OpenNlpSegmenter.class);
			posTagger = AnalysisEngineFactory.createEngine(OpenNlpPosTagger.class);
			lemmatizer = AnalysisEngineFactory.createEngine(StanfordLemmatizer.class);
			if (computeDependencies) {
				depParser = AnalysisEngineFactory.createEngine(MaltParser.class);
			}
			jCas = CasCreationUtils.createCas(createTypeSystemDescription(), null, null).getJCas();
		} catch (ResourceInitializationException e) {
			log.error("Couldn't initialize analysis engine", e);
		} catch (CASException e) {
			log.error("Couldn't create new CAS", e);
		}
		generatePseudoSenses = context.getConfiguration().getBoolean("holing.genpseudosenses", false);
		generatePseudoSensesNum = context.getConfiguration().getInt("holing.genpseudosenses.numsenses", 2);
		String generatePseudoSensesWordsStr = context.getConfiguration().get("holing.genpseudosenses.words");
		if (generatePseudoSensesWordsStr != null) {
			generatePseudoSensesWords = new HashSet<String>(
					Arrays.asList(generatePseudoSensesWordsStr.split(",")));
		}
		log.info("Computing coocs: " + computeCoocs);
		log.info("Computing dependencies: " + computeDependencies);
		log.info("Semantifying dependencies: " + semantifyDependencies);
		log.info("Ready!");
	}
	
	@Override
	public void map(LongWritable key, Text value, Context context)
		throws IOException, InterruptedException {
		try {
			String text = value.toString();
			jCas.reset();
			jCas.setDocumentText(text);
			jCas.setDocumentLanguage("en");
			segmenter.process(jCas);
			Collection<Token> tokens = JCasUtil.select(jCas, Token.class);
			Set<String> tokenSet = new HashSet<String>();
			if (tokens.size() > maxSentenceLength) {
				context.getCounter("de.tudarmstadt.lt.wsi", "NUM_SKIPPED_SENTENCES").increment(1);
				return;
			} else {
				context.getCounter("de.tudarmstadt.lt.wsi", "NUM_PROCESSED_SENTENCES").increment(1);
			}

			posTagger.process(jCas);
			lemmatizer.process(jCas);
			
			Random r = new Random();
			
			Map<Token, String> tokenLemmas = new HashMap<Token, String>();
			
			for (Token token : tokens) {
				String lemma = token.getLemma().getValue();
				// every 1000th word is replaced by "random word" placeholder
				if (generatePseudoSenses && r.nextInt(1000) == 0) {
					lemma = "__RANDOM__";
				} else if (generatePseudoSenses &&
					(generatePseudoSensesWords == null || generatePseudoSensesWords.contains(lemma))) {
					int sense = r.nextInt(generatePseudoSensesNum);
					lemma += "$$" + sense;
				}
				tokenLemmas.put(token, lemma);
				tokenSet.add(lemma);
				context.write(new Text("W\t" + lemma), ONE);
				String pos = token.getPos().getPosValue();
				if (pos.equals("NN") || pos.equals("NNS")) {
					context.write(new Text("WNouns\t" + lemma), ONE);
				}
			}
			
			if (computeCoocs) {
				for (String lemma : tokenSet) {
					context.write(new Text("CoocF\t" + lemma), ONE);
				}
				
				for (String lemma : tokenSet) {
//					String pos = token.getPos().getPosValue();
//					String lemma = token.getLemma().getValue();
//					if (pos.equals("NN") || pos.equals("NNS")) {
						for (String lemma2 : tokenSet) {
							context.write(new Text("CoocWF\t" + lemma + "\t" + lemma2), ONE);
						}
//					}
					context.progress();
				}
			}
			
			if (computeDependencies) {
				depParser.process(jCas);
				Collection<Dependency> deps = JCasUtil.select(jCas, Dependency.class);
				Collection<Dependency> depsCollapsed = collapseDependencies(jCas, deps, tokens);
				for (Dependency dep : depsCollapsed) {
					Token source = dep.getGovernor();
					Token target = dep.getDependent();
					String rel = dep.getDependencyType();
					if (semantifyDependencies) {
						rel = semantifyDependencyRelation(rel);
						if (rel == null) {
							continue;
						}
					}
					String sourcePos = source.getPos().getPosValue();
					String targetPos = target.getPos().getPosValue();
					String sourceLemma = tokenLemmas.get(source);//source.getLemma().getValue();
					String targetLemma = tokenLemmas.get(target);//target.getLemma().getValue();
					if (sourcePos.equals("NN") || sourcePos.equals("NNS"))
					{
						String bim = rel + "(@@," + targetLemma + ")";
						context.write(new Text("DepF\t" + bim), ONE);
						context.write(new Text("DepWF\t" + sourceLemma + "\t" + bim), ONE);
					}
					if (targetPos.equals("NN") || targetPos.equals("NNS"))
					{
						String bim = rel + "(" + sourceLemma + ",@@)";
						context.write(new Text("DepF\t" + bim), ONE);
						context.write(new Text("DepWF\t" + targetLemma + "\t" + bim), ONE);
					}
					context.progress();
					
				}
			}
		} catch (Exception e) {
			log.error("Can't process line: " + value.toString(), e);
			context.getCounter("de.tudarmstadt.lt.wiki", "NUM_MAP_ERRORS").increment(1);
		}
	}
	
	private Collection<Dependency> collapseDependencies(JCas jCas, Collection<Dependency> deps, Collection<Token> tokens) {
		List<Dependency> collapsedDeps = new ArrayList<>(deps);
		for (Token token : tokens) {
			if (token.getPos().getPosValue().equals("IN")) {
				List<Dependency> toRemove = new ArrayList<>();
				String depType = "prep_" + token.getCoveredText().toLowerCase();
				Token source = null;
				Token target = null;
				int begin = -1;
				int end = -1;
				for (Dependency dep : collapsedDeps) {
					if (dep.getGovernor() == token && dep.getDependencyType().toLowerCase().equals("mwe")) {
						depType = "prep_" + dep.getDependent().getCoveredText() + "_" + token.getCoveredText().toLowerCase();
						toRemove.add(dep);
					} else if (dep.getGovernor() == token && dep.getDependencyType().toLowerCase().equals("pobj")) {
						end = dep.getEnd();
						target = dep.getDependent();
						toRemove.add(dep);
					} else if (dep.getDependent() == token && dep.getDependencyType().toLowerCase().equals("prep")) {
						begin = dep.getBegin();
						source = dep.getGovernor();
						toRemove.add(dep);
					}
				}
				if (source != null && target != null) {
					Dependency collapsedDep = new Dependency(jCas, begin, end);
					collapsedDep.setGovernor(source);
					collapsedDep.setDependent(target);
					collapsedDep.setDependencyType(depType);
					collapsedDeps.add(collapsedDep);
					collapsedDeps.removeAll(toRemove);
				}
			}
		}
		return collapsedDeps;
	}
	
	private String semantifyDependencyRelation(String rel) {
		switch (rel) {
		case "nsubj":
			return "subj";
		case "nsubjpass":
		case "partmod":
		case "infmod":
		case "vmod":
		case "dobj":
			return "obj";
		}
		return null;
	}
}
