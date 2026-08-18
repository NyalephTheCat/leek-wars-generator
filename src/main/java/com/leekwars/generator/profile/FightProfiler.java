package com.leekwars.generator.profile;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.leekwars.generator.fight.entity.EntityAI;
import com.leekwars.generator.state.Entity;

import leekscript.runner.Profiler;

/**
 * Collecte les profils d'un combat et les écrit au format <i>folded stacks</i>.
 *
 * <p>Les IA sont enregistrées à leur construction plutôt que retrouvées en fin de combat :
 * {@code Fight.finishFight} supprime les invocations avant la fin, et un parcours des entités
 * survivantes en raterait.
 *
 * <p><b>Un fichier par entité.</b> Une invocation exécute la fonction d'IA de son invocateur
 * <em>sur l'objet AI de celui-ci</em> : ses opérations et ses frames atterrissent donc dans
 * l'arbre de l'invocateur. Elles y forment néanmoins leur propre tour racine, à son nom, ce qui
 * permet de redécouper l'arbre et de donner à chaque entité son flamegraph.
 */
public class FightProfiler {

	/** Une ligne de la série par tour. */
	public record TurnSample(int entityId, String entityName, int turn, long ops, long wallNanos) {}

	private final List<Entry> entries = new ArrayList<>();
	private final List<TurnSample> turns = new ArrayList<>();
	/** Dernier total lu par profileur, pour calculer le delta d'un tour. */
	private final Map<Profiler, Long> lastTotals = new IdentityHashMap<>();

	/** L'entité, le profileur qui porte son profil (celui de son maître pour une invocation)
	 *  et le libellé de sa tour racine dans cet arbre. */
	private record Entry(Entity entity, Profiler profiler, String rootLabel, boolean ownsTree) {}

	/** Enregistre une entité dont le travail sera profilé. Sans effet hors mode profil. */
	public void register(Entity entity, EntityAI ai) {
		if (entity == null || ai == null) return;
		var profiler = ai.profileHost().getProfiler();
		if (profiler == null) return;
		// ownsTree : l'IA porte son propre arbre. Faux pour une invocation, qui n'a qu'une tour
		// racine dans l'arbre de son maitre — et ne doit donc pas hériter des racines de hooks.
		entries.add(new Entry(entity, profiler, EntityAI.profileLabel(entity), ai.getProfiler() != null));
	}

	/**
	 * Enregistre le coût d'un tour. Le delta est lu sur le profileur <i>hôte</i> : pendant le
	 * tour d'une invocation, lui seul avance.
	 */
	public void recordTurn(Entity entity, int turn, long wallNanos) {
		var ai = entity.getAI();
		if (!(ai instanceof EntityAI entityAI)) return;
		var profiler = entityAI.profileHost().getProfiler();
		if (profiler == null) return;
		long total = profiler.getTotalSelfOps();
		long previous = lastTotals.getOrDefault(profiler, 0L);
		lastTotals.put(profiler, total);
		turns.add(new TurnSample(entity.getFId(), entity.getName(), turn, total - previous, wallNanos));
	}

	public boolean isEmpty() {
		return entries.isEmpty();
	}

	/** Écrit les fichiers du combat sous {@code <dir>/fight-<id>/}. */
	public Path write(Path root, int fightId) throws IOException {
		var dir = root.resolve("fight-" + fightId);
		Files.createDirectories(dir);

		// Les tours racines des AUTRES entités : à exclure du fichier d'une entité, sinon le
		// profil d'un invocateur contiendrait aussi celui de ses invocations.
		Set<String> allRoots = new LinkedHashSet<>();
		for (var entry : entries) allRoots.add(entry.rootLabel());

		for (var entry : entries) {
			var mine = mineOnly(entry, allRoots);
			try (var out = writer(dir.resolve("entity-" + entry.entity().getFId() + "-" + fileName(entry.entity()) + ".folded"))) {
				entry.profiler().writeFolded(out, null, mine);
			}
		}

		// Fusion : chaque arbre une seule fois (invocateur et invocations le partagent).
		try (var merged = writer(dir.resolve("merged.folded"))) {
			Set<Profiler> written = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
			for (var entry : entries) {
				if (written.add(entry.profiler())) {
					entry.profiler().writeFolded(merged, null);
				}
			}
		}

		try (var out = writer(dir.resolve("turns.csv"))) {
			out.write("entity_id,entity_name,turn,ops,wall_ns\n");
			for (var sample : turns) {
				out.write(sample.entityId() + "," + sample.entityName().replace(',', '_') + ","
						+ sample.turn() + "," + sample.ops() + "," + sample.wallNanos() + "\n");
			}
		}

		try (var out = writer(dir.resolve("summary.txt"))) {
			out.write("fight " + fightId + "\n");
			for (var entry : entries) {
				var profiler = entry.profiler();
				out.write(String.format("entity %d %s: %d ops%s%n",
						entry.entity().getFId(), entry.rootLabel(),
						profiler.getSelfOps(mineOnly(entry, allRoots)),
						profiler.isTruncated() ? " (TRONQUE: plafond de contextes atteint)" : ""));
			}
			out.write("\nflamegraph.pl --countname ops entity-<id>-<nom>.folded > flame.svg\n");
			out.write("merged.folded reunit toutes les entites ; deposable sur https://speedscope.app\n");
		}

		return dir;
	}

	/**
	 * Filtre gardant les tours racines de cette entité : la sienne, plus — pour celle qui porte
	 * l'arbre — les racines qui n'appartiennent à aucune entité (les hooks, dont la racine
	 * porte le nom du hook).
	 */
	private static java.util.function.Predicate<String> mineOnly(Entry entry, Set<String> allRoots) {
		return label -> entry.rootLabel().equals(label)
				|| (entry.ownsTree() && !allRoots.contains(label));
	}

	private static Writer writer(Path path) throws IOException {
		return Files.newBufferedWriter(path, StandardCharsets.UTF_8);
	}

	/** Nom d'entité utilisable en nom de fichier. */
	private static String fileName(Entity entity) {
		var name = entity.getName();
		if (name == null || name.isEmpty()) name = "entity";
		return name.replaceAll("[^A-Za-z0-9_.#-]", "_");
	}
}
