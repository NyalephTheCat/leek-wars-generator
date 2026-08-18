package com.leekwars.generator.profile;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.leekwars.generator.fight.entity.EntityAI;
import com.leekwars.generator.state.Entity;

import leekscript.runner.Profiler;

/**
 * Collecte les profils d'un combat et les écrit au format <i>folded stacks</i>.
 *
 * <p>Les IA profilées sont enregistrées à leur construction plutôt que retrouvées en fin de
 * combat : {@code Fight.finishFight} supprime les invocations avant la fin, et un parcours des
 * entités survivantes raterait des IA.
 */
public class FightProfiler {

	/** Une ligne de la série par tour. */
	public record TurnSample(int entityId, String entityName, int turn, long ops, long wallNanos) {}

	private final List<Entry> entries = new ArrayList<>();
	private final List<TurnSample> turns = new ArrayList<>();
	/** Dernier total lu par profileur, pour calculer le delta d'un tour. */
	private final Map<Profiler, Long> lastTotals = new IdentityHashMap<>();

	private record Entry(Entity entity, EntityAI ai) {}

	/** Enregistre une IA qui porte son propre profil (les invocations écrivent chez leur maître). */
	public void register(Entity entity, EntityAI ai) {
		if (ai == null || ai.getProfiler() == null) return;
		entries.add(new Entry(entity, ai));
	}

	/**
	 * Enregistre le coût d'un tour. Le delta est lu sur le profileur <i>hôte</i> : le travail
	 * d'une invocation est facturé au compteur de son maître, pas au sien.
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

		try (var merged = writer(dir.resolve("merged.folded"))) {
			for (var entry : entries) {
				var profiler = entry.ai().getProfiler();
				var name = label(entry.entity());
				try (var out = writer(dir.resolve("entity-" + entry.entity().getFId() + "-" + name + ".folded"))) {
					profiler.writeFolded(out, null);
				}
				// Pas de préfixe : la frame racine porte déjà le nom de l'entité (et celui de
				// chaque invocation, dont le profil vit dans l'arbre de son maître).
				profiler.writeFolded(merged, null);
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
				var profiler = entry.ai().getProfiler();
				out.write(String.format("entity %d %s: %d ops, %d contexts%s%n",
						entry.entity().getFId(), label(entry.entity()),
						profiler.getTotalSelfOps(), profiler.getNodeCount(),
						profiler.isTruncated() ? " (TRONQUE: plafond de contextes atteint)" : ""));
			}
			out.write("\nflamegraph.pl --countname ops merged.folded > flame.svg\n");
			out.write("ou deposer merged.folded sur https://speedscope.app\n");
		}

		return dir;
	}

	private static Writer writer(Path path) throws IOException {
		return Files.newBufferedWriter(path, StandardCharsets.UTF_8);
	}

	/** Nom d'entité utilisable en nom de fichier et en libellé de frame. */
	private static String label(Entity entity) {
		var name = entity.getName();
		if (name == null || name.isEmpty()) name = "entity";
		return name.replaceAll("[^A-Za-z0-9_.#-]", "_") + "#" + entity.getFId();
	}
}
