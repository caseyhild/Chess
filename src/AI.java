import java.util.*;

/**
 * Chess AI using minimax with alpha-beta pruning.
 *
 * Evaluation uses:
 *   - Material values (pawn=100, knight=320, bishop=330, rook=500, queen=900)
 *   - Piece-square tables (positional bonuses)
 *   - Move ordering (captures first) to improve alpha-beta cutoffs
 */
public class AI
{
    private static final int DEPTH = 4;
    private static final int RANDOM_MARGIN = 30; // centipawns — moves within this of best are equally eligible
    private final Random random = new Random();

    private static final int PAWN_VALUE   = 100;
    private static final int KNIGHT_VALUE = 320;
    private static final int BISHOP_VALUE = 330;
    private static final int ROOK_VALUE   = 500;
    private static final int QUEEN_VALUE  = 900;
    private static final int KING_VALUE   = 20000;

    // Piece-square tables from white's perspective (row 0 = opponent's back rank).
    // Values are bonuses/penalties for having a piece on that square.
    // These are the standard tables used in many open-source chess engines.

    private static final int[][] PAWN_TABLE = {
        {  0,  0,  0,  0,  0,  0,  0,  0 },
        { 50, 50, 50, 50, 50, 50, 50, 50 },
        { 10, 10, 20, 30, 30, 20, 10, 10 },
        {  5,  5, 10, 25, 25, 10,  5,  5 },
        {  0,  0,  0, 20, 20,  0,  0,  0 },
        {  5, -5,-10,  0,  0,-10, -5,  5 },
        {  5, 10, 10,-20,-20, 10, 10,  5 },
        {  0,  0,  0,  0,  0,  0,  0,  0 },
    };

    private static final int[][] KNIGHT_TABLE = {
        {-50,-40,-30,-30,-30,-30,-40,-50 },
        {-40,-20,  0,  0,  0,  0,-20,-40 },
        {-30,  0, 10, 15, 15, 10,  0,-30 },
        {-30,  5, 15, 20, 20, 15,  5,-30 },
        {-30,  0, 15, 20, 20, 15,  0,-30 },
        {-30,  5, 10, 15, 15, 10,  5,-30 },
        {-40,-20,  0,  5,  5,  0,-20,-40 },
        {-50,-40,-30,-30,-30,-30,-40,-50 },
    };

    private static final int[][] BISHOP_TABLE = {
        {-20,-10,-10,-10,-10,-10,-10,-20 },
        {-10,  0,  0,  0,  0,  0,  0,-10 },
        {-10,  0,  5, 10, 10,  5,  0,-10 },
        {-10,  5,  5, 10, 10,  5,  5,-10 },
        {-10,  0, 10, 10, 10, 10,  0,-10 },
        {-10, 10, 10, 10, 10, 10, 10,-10 },
        {-10,  5,  0,  0,  0,  0,  5,-10 },
        {-20,-10,-10,-10,-10,-10,-10,-20 },
    };

    private static final int[][] ROOK_TABLE = {
        {  0,  0,  0,  0,  0,  0,  0,  0 },
        {  5, 10, 10, 10, 10, 10, 10,  5 },
        { -5,  0,  0,  0,  0,  0,  0, -5 },
        { -5,  0,  0,  0,  0,  0,  0, -5 },
        { -5,  0,  0,  0,  0,  0,  0, -5 },
        { -5,  0,  0,  0,  0,  0,  0, -5 },
        { -5,  0,  0,  0,  0,  0,  0, -5 },
        {  0,  0,  0,  5,  5,  0,  0,  0 },
    };

    private static final int[][] QUEEN_TABLE = {
        {-20,-10,-10, -5, -5,-10,-10,-20 },
        {-10,  0,  0,  0,  0,  0,  0,-10 },
        {-10,  0,  5,  5,  5,  5,  0,-10 },
        { -5,  0,  5,  5,  5,  5,  0, -5 },
        {  0,  0,  5,  5,  5,  5,  0, -5 },
        {-10,  5,  5,  5,  5,  5,  0,-10 },
        {-10,  0,  5,  0,  0,  0,  0,-10 },
        {-20,-10,-10, -5, -5,-10,-10,-20 },
    };

    private static final int[][] KING_MIDDLE_TABLE = {
        {-30,-40,-40,-50,-50,-40,-40,-30 },
        {-30,-40,-40,-50,-50,-40,-40,-30 },
        {-30,-40,-40,-50,-50,-40,-40,-30 },
        {-30,-40,-40,-50,-50,-40,-40,-30 },
        {-20,-30,-30,-40,-40,-30,-30,-20 },
        {-10,-20,-20,-20,-20,-20,-20,-10 },
        { 20, 20,  0,  0,  0,  0, 20, 20 },
        { 20, 30, 10,  0,  0, 10, 30, 20 },
    };

    /**
     * Returns the best move for the current player on the given board.
     * The board must be oriented so that the AI is the current `color`.
     */
    public Move getBestMove(Board board)
    {
        ArrayList<Move> moves = board.getMoves();
        board.addCastleMove(moves);
        board.removeInCheckMoves(moves);

        if(moves.isEmpty())
            return null;

        orderMoves(moves, board);

        // Evaluate each move once and store scores
        int[] scores = new int[moves.size()];
        int bestScore = Integer.MIN_VALUE;

        for(int i = 0; i < moves.size(); i++)
        {
            ArrayList<Piece> savedPieces = deepCopyPieces(board.pieces);
            Move savedPrevMove = board.previousMove;
            Piece savedPieceMoved = board.pieceMoved;
            boolean[] savedWhiteCastle = board.whiteCastle.clone();
            boolean[] savedBlackCastle = board.blackCastle.clone();

            board.move(moves.get(i));
            board.update(moves.get(i));
            board.flipBoard();

            scores[i] = -minimax(board, DEPTH - 1, Integer.MIN_VALUE + 1, Integer.MAX_VALUE);

            board.flipBoard();
            board.pieces = savedPieces;
            board.previousMove = savedPrevMove;
            board.pieceMoved = savedPieceMoved;
            board.whiteCastle = savedWhiteCastle;
            board.blackCastle = savedBlackCastle;

            if(scores[i] > bestScore)
                bestScore = scores[i];
        }

        // Pick randomly among all moves within RANDOM_MARGIN of the best score
        ArrayList<Move> topMoves = new ArrayList<>();
        for(int i = 0; i < moves.size(); i++)
            if(scores[i] >= bestScore - RANDOM_MARGIN)
                topMoves.add(moves.get(i));

        return topMoves.get(random.nextInt(topMoves.size()));
    }

    /**
     * Negamax (symmetric minimax): returns the score from the perspective
     * of whoever's turn it is on `board`.
     */
    private int minimax(Board board, int depth, int alpha, int beta)
    {
        if(depth == 0)
            return evaluate(board);

        ArrayList<Move> moves = board.getMoves();
        board.addCastleMove(moves);
        board.removeInCheckMoves(moves);

        if(moves.isEmpty())
        {
            if(board.inCheck())
                return -(KING_VALUE + depth * 100); // checkmate - prefer faster mates
            return 0; // stalemate
        }

        orderMoves(moves, board);

        for(Move move : moves)
        {
            ArrayList<Piece> savedPieces = deepCopyPieces(board.pieces);
            Move savedPrevMove = board.previousMove;
            Piece savedPieceMoved = board.pieceMoved;
            boolean[] savedWhiteCastle = board.whiteCastle.clone();
            boolean[] savedBlackCastle = board.blackCastle.clone();

            board.move(move);
            board.update(move);
            board.flipBoard();

            int score = -minimax(board, depth - 1, -beta, -alpha);

            board.flipBoard();
            board.pieces = savedPieces;
            board.previousMove = savedPrevMove;
            board.pieceMoved = savedPieceMoved;
            board.whiteCastle = savedWhiteCastle;
            board.blackCastle = savedBlackCastle;

            if(score >= beta)
                return beta; // beta cutoff

            if(score > alpha)
                alpha = score;
        }

        return alpha;
    }

    /**
     * Evaluates the board from the perspective of the current player (board.color).
     * Positive = good for current player.
     */
    private int evaluate(Board board)
    {
        int score = 0;
        for(Piece p : board.pieces)
        {
            int materialValue = materialValue(p.type);
            int positionalBonus = positionalBonus(p, board.color);
            int advancementBonus = advancementBonus(p, board.color);

            if(p.color.equals(board.color))
                score += materialValue + positionalBonus + advancementBonus;
            else
                score -= materialValue + positionalBonus + advancementBonus;
        }
        return score;
    }

    /**
     * Extra bonus for pawns that are close to promotion.
     * Row 0 = promotion rank, row 6 = starting rank (from current player's perspective).
     * The bonus scales sharply as the pawn advances past the midpoint.
     */
    private int advancementBonus(Piece p, String currentColor)
    {
        if (!p.type.equals("pawn")) return 0;
        // Use the piece's row from its own side's perspective
        int row = p.color.equals(currentColor) ? p.row : 7 - p.row;
        // row 6 = home, row 0 = promotion
        // Bonus: 0 at rows 6-4, then escalating
        return switch (row) {
            case 3 -> 20;
            case 2 -> 60;
            case 1 -> 150;
            default -> 0;
        }; // one step from queening — very valuable
    }

    private int materialValue(String type)
    {
        return switch (type) {
            case "pawn" -> PAWN_VALUE;
            case "knight" -> KNIGHT_VALUE;
            case "bishop" -> BISHOP_VALUE;
            case "rook" -> ROOK_VALUE;
            case "queen" -> QUEEN_VALUE;
            case "king" -> KING_VALUE;
            default -> 0;
        };
    }

    /**
     * Returns the piece-square table bonus for piece p.
     * The tables are from the perspective of the current player -
     * row 7 is the player's back rank (where their pieces start),
     * so we flip row index for the opponent's pieces.
     */
    private int positionalBonus(Piece p, String currentColor)
    {
        // Row in the piece-square table: our pieces advance upward (row decreases),
        // so row 7 is home rank. The tables have index 0 as the far end.
        int tableRow = p.row; // board is already oriented: row 7 = current player's back rank
        int tableCol = p.col;

        // If the piece belongs to the opponent, mirror the row
        if(!p.color.equals(currentColor))
            tableRow = 7 - tableRow;

        return switch (p.type) {
            case "pawn" -> PAWN_TABLE[tableRow][tableCol];
            case "knight" -> KNIGHT_TABLE[tableRow][tableCol];
            case "bishop" -> BISHOP_TABLE[tableRow][tableCol];
            case "rook" -> ROOK_TABLE[tableRow][tableCol];
            case "queen" -> QUEEN_TABLE[tableRow][tableCol];
            case "king" -> KING_MIDDLE_TABLE[tableRow][tableCol];
            default -> 0;
        };
    }

    /**
     * Orders moves to improve alpha-beta pruning efficiency.
     * Captures are placed first (sorted by Most Valuable Victim - Least Valuable Attacker).
     */
    private void orderMoves(ArrayList<Move> moves, Board board)
    {
        moves.sort((a, b) -> {
            int scoreA = moveScore(a, board);
            int scoreB = moveScore(b, board);
            return scoreB - scoreA; // descending
        });
    }

    private int moveScore(Move move, Board board)
    {
        Piece victim = board.getPiece(move.endRow, move.endCol);
        Piece attacker = board.getPiece(move.startRow, move.startCol);
        if(victim != null && attacker != null)
            return 10 * materialValue(victim.type) - materialValue(attacker.type);
        return 0;
    }

    private ArrayList<Piece> deepCopyPieces(ArrayList<Piece> pieces)
    {
        ArrayList<Piece> copy = new ArrayList<>();
        for(Piece p : pieces)
        {
            Piece np = new Piece(p.row, p.col, p.color, 0);
            np.type = p.type;
            copy.add(np);
        }
        return copy;
    }
}
