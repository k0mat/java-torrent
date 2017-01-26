package pl.uksw.edu.javatorrent.client.strategy;

import pl.uksw.edu.javatorrent.client.Piece;

import java.util.BitSet;
import java.util.SortedSet;

/**
 * A sequential request strategy implementation.
 *
 * @author cjmalloy
 *
 */
public class RequestStrategyImplSequential implements RequestStrategy {

	@Override
	public Piece choosePiece(SortedSet<Piece> rarest, BitSet interesting, Piece[] pieces) {

		for (Piece p : pieces) {
			if (interesting.get(p.getIndex())) return p;
		}
		return null;
	}
}
