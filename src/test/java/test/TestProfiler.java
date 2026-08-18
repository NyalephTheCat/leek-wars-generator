package test;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import com.leekwars.generator.fight.entity.EntityAI;
import com.leekwars.generator.leek.Leek;

import leekscript.runner.Profiler;

/**
 * Profileur d'IA : flamegraph pondéré par le compteur d'opérations, accumulé sur tout le combat.
 *
 * <p>L'invariant central est la <b>somme</b> : le coût propre de tous les contextes d'appel doit
 * valoir, à l'opération près, le total facturé à l'entité. Il valide d'un coup l'arithmétique
 * self/inclusive, l'appariement enter/exit et l'accumulation par-dessus la remise à zéro du
 * compteur à chaque tour.
 */
public class TestProfiler extends FightTestBase {

	private Leek leek1;
	private Leek leek2;

	@Override
	protected void createLeeks() {
		leek1 = defaultLeek(1, "Profiled");
		leek2 = defaultLeek(2, "Dummy");
		fight.getState().addEntity(0, leek1);
		fight.getState().addEntity(1, leek2);
	}

	@After
	public void disableProfiling() {
		generator.setProfileDir(null);
	}

	private void profile(Path dir) {
		generator.setProfileDir(dir == null ? Path.of("profile") : dir);
	}

	private Profiler profilerOf(Leek leek) {
		return ((EntityAI) leek.getAI()).getProfiler();
	}

	private static final String AI =
		"function costly() {"
		+ "  var s = 0;"
		+ "  for (var i = 0; i < 200; i++) { s += i; }"
		+ "  return s;"
		+ "}"
		+ "function cheap() { return 1; }"
		+ "function outer() { cheap(); return costly(); }"
		+ "outer();";

	@Test
	public void selfOpsSumEqualsOperationsBilledToTheEntity() throws Exception {
		profile(null);
		attachAI(leek1, AI);
		attachAI(leek2, "1;");
		runFight();

		var profiler = profilerOf(leek1);
		Assert.assertNotNull("le profileur doit être installé en mode profil", profiler);
		Assert.assertEquals("somme des coûts propres == opérations facturées",
			leek1.getTotalOperations(), profiler.getTotalSelfOps());
		// Même valeur recalculée en parcourant l'arbre : le total incrémental ne dérive pas.
		Assert.assertEquals(profiler.getTotalSelfOps(), profiler.computeTotalSelfOps());
	}

	@Test
	public void profilingDoesNotChangeTheFight() throws Exception {
		attachAI(leek1, AI);
		attachAI(leek2, "1;");
		runFight();
		long plainOps = leek1.getTotalOperations();
		String plainActions = fight.getState().getActions().toJSON().get("actions").toString();

		setUp(); // combat neuf, même graine
		profile(null);
		attachAI(leek1, AI);
		attachAI(leek2, "1;");
		runFight();

		Assert.assertEquals("le profilage ne doit rien changer aux opérations facturées",
			plainOps, leek1.getTotalOperations());
		Assert.assertEquals("ni au déroulé du combat",
			plainActions, fight.getState().getActions().toJSON().get("actions").toString());
	}

	@Test
	public void costIsAttributedToTheExpensiveFunction() throws Exception {
		profile(null);
		attachAI(leek1, AI);
		attachAI(leek2, "1;");
		runFight();

		var folded = folded(profilerOf(leek1));
		long costly = weightOf(folded, ":costly");
		long cheap = weightOf(folded, ":cheap");
		Assert.assertTrue("costly doit porter l'essentiel du coût : " + folded, costly > 1000);
		Assert.assertTrue("cheap doit être négligeable devant costly : " + folded, cheap * 10 < costly);
		Assert.assertTrue("la pile doit refléter l'imbrication réelle : " + folded,
			folded.contains(":outer;") && folded.contains(":outer;") && folded.contains("costly"));
	}

	@Test
	public void treeAccumulatesAcrossTurnsNotJustOne() throws Exception {
		profile(null);
		attachAI(leek1, AI);
		attachAI(leek2, "1;");
		runFight();

		var profiler = profilerOf(leek1);
		// Le compteur est remis à zéro à chaque tour : sans accumulation, le total vaudrait
		// celui d'un seul tour.
		Assert.assertTrue("le profil doit couvrir tout le combat",
			profiler.getTotalSelfOps() > 10 * perTurnUpperBound());
		Assert.assertTrue(callsOfCostly(profiler) > 30);
	}

	@Test
	public void budgetExhaustionLeavesAConsistentTree() throws Exception {
		profile(null);
		attachAI(leek1, "function boucle() { while (true) { } } boucle();");
		attachAI(leek2, "1;");
		runFight();

		var profiler = profilerOf(leek1);
		Assert.assertEquals("le dépassement de budget ne doit pas fausser la comptabilité",
			leek1.getTotalOperations(), profiler.getTotalSelfOps());
		Assert.assertTrue("la fonction fautive doit être visible", folded(profiler).contains(":boucle"));
	}

	@Test
	public void deepRecursionLeavesAConsistentTree() throws Exception {
		profile(null);
		attachAI(leek1, "function f(n) { return f(n + 1); } f(0);");
		attachAI(leek2, "1;");
		runFight();

		var profiler = profilerOf(leek1);
		Assert.assertEquals("un débordement de pile ne doit pas fausser la comptabilité",
			leek1.getTotalOperations(), profiler.getTotalSelfOps());
	}

	@Test
	public void writesFoldedStacksTurnsAndSummary() throws Exception {
		Path dir = Files.createTempDirectory("lw-profile");
		profile(dir);
		attachAI(leek1, AI);
		attachAI(leek2, "1;");
		runFight();

		Path out = dir.resolve("fight-" + fight.getId());
		Assert.assertTrue(Files.isDirectory(out));
		Assert.assertTrue(Files.exists(out.resolve("merged.folded")));
		Assert.assertTrue(Files.exists(out.resolve("summary.txt")));

		// Format folded : « pile poids », le poids étant un entier après la dernière espace.
		long total = 0;
		for (String line : Files.readAllLines(out.resolve("merged.folded"))) {
			int sep = line.lastIndexOf(' ');
			Assert.assertTrue("ligne folded mal formée : " + line, sep > 0);
			Assert.assertFalse("un libellé ne doit jamais contenir d'espace : " + line,
				line.substring(0, sep).contains(" "));
			total += Long.parseLong(line.substring(sep + 1));
		}
		Assert.assertEquals("le fichier fusionné doit totaliser les opérations des deux entités",
			leek1.getTotalOperations() + leek2.getTotalOperations(), total);

		// La série par tour doit totaliser la même chose pour l'entité profilée.
		long fromTurns = Files.readAllLines(out.resolve("turns.csv")).stream()
			.skip(1)
			.map(l -> l.split(","))
			.filter(c -> Integer.parseInt(c[0]) == leek1.getFId())
			.mapToLong(c -> Long.parseLong(c[3]))
			.sum();
		Assert.assertEquals(leek1.getTotalOperations(), fromTurns);
	}

	/**
	 * Cote polyglot il n'y a pas de generation de code a instrumenter : les frames viennent d'un
	 * ExecutionListener en granularite RACINE attache a l'engine (cf PolyglotSandbox), et le poids
	 * combine les statements guest calibres et le compteur hote.
	 */
	@Test
	public void jsAiProducesAFlamegraphToo() throws Exception {
		profile(null);
		attachJsAI(leek1,
			"function costly(n){var s=0;for(var i=0;i<n;i++){s+=i;}return s;}"
			+ "function cheap(){return 1;}"
			+ "function turn(){cheap();costly(200);}");
		attachAI(leek2, "1;");
		runFight();

		Assert.assertTrue("leek1 doit utiliser une IA polyglot",
			leek1.getAI() instanceof com.leekwars.generator.polyglot.PolyglotEntityAI);
		var profiler = profilerOf(leek1);
		Assert.assertNotNull(profiler);
		var folded = folded(profiler);
		Assert.assertTrue("les fonctions guest doivent apparaitre : " + folded, folded.contains(";costly"));
		Assert.assertTrue("l'imbrication guest doit etre conservee : " + folded, folded.contains("turn;costly"));
		Assert.assertTrue("costly doit dominer cheap : " + folded,
			weightOf(folded, ";costly") > 10 * Math.max(1, weightOf(folded, ";cheap")));
	}

	/** IA JS : le routage polyglot se fait sur l'extension du chemin de l'AIFile. */
	private void attachJsAI(Leek leek, String code) {
		var file = new leekscript.compiler.AIFile("profiler_test_" + System.nanoTime() + ".js", code,
			System.currentTimeMillis(), leekscript.compiler.LeekScript.LATEST_VERSION, leek.getId(), false);
		leek.setAIFile(file);
		leek.setLogs(new com.leekwars.generator.leek.LeekLog(farmerLog, leek));
		leek.setFight(fight);
		leek.setBirthTurn(1);
	}

	@Test
	public void noProfilerWithoutTheFlag() throws Exception {
		attachAI(leek1, AI);
		attachAI(leek2, "1;");
		runFight();
		Assert.assertNull("aucun profileur ne doit être installé hors mode profil", profilerOf(leek1));
	}

	private static String folded(Profiler profiler) throws Exception {
		var out = new StringWriter();
		profiler.writeFolded(out, null);
		return out.toString();
	}

	private static long weightOf(String folded, String frameSuffix) {
		long total = 0;
		for (String line : folded.split("\n")) {
			int sep = line.lastIndexOf(' ');
			if (sep <= 0) continue;
			String stack = line.substring(0, sep);
			if (stack.endsWith(frameSuffix)) total += Long.parseLong(line.substring(sep + 1));
		}
		return total;
	}

	private long perTurnUpperBound() {
		return 2000; // largement au-dessus du coût d'un tour de l'IA de test
	}

	private static long callsOfCostly(Profiler profiler) {
		return countCalls(profiler.getRoot(), profiler);
	}

	private static long countCalls(Profiler.Node node, Profiler profiler) {
		long total = profiler.getLabel(node.getFrameId()).endsWith(":costly") ? node.getCalls() : 0;
		for (var child : node.getChildren()) {
			total += countCalls(child, profiler);
		}
		return total;
	}
}
