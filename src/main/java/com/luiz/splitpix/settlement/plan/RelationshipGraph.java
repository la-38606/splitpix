package com.luiz.splitpix.settlement.plan;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Who has a financial relationship with whom, as an undirected graph. Two
 * participants are related when one paid an expense in which the other held a
 * positive share, or when a completed settlement exists between them, in
 * either direction (docs/adr/0010-relationship-graph.md).
 */
public final class RelationshipGraph {

	public record Edge(UUID a, UUID b) {
	}

	public static final RelationshipGraph EMPTY = new RelationshipGraph(Set.of());

	private final Set<String> pairs;

	private RelationshipGraph(Set<String> pairs) {
		this.pairs = pairs;
	}

	public static RelationshipGraph of(Collection<Edge> edges) {
		Set<String> pairs = new HashSet<>();
		for (Edge edge : edges) {
			if (!edge.a().equals(edge.b())) {
				pairs.add(key(edge.a(), edge.b()));
			}
		}
		return new RelationshipGraph(Set.copyOf(pairs));
	}

	public boolean related(UUID a, UUID b) {
		return pairs.contains(key(a, b));
	}

	private static String key(UUID a, UUID b) {
		return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
	}

}
