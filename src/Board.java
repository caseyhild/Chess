import java.util.*;

public class Board
{
    public int width;
    public int height;
    public String myColor;
    public String color;
    public ArrayList<Piece> pieces;
    public Move previousMove;
    public Piece pieceMoved;
    public boolean[] whiteCastle;
    public boolean[] blackCastle;

    /** Deep-copy constructor — produces an independent clone of the given board. */
    public Board(Board other)
    {
        width = other.width;
        height = other.height;
        myColor = other.myColor;
        color = other.color;
        pieces = new ArrayList<>();
        for(Piece p : other.pieces)
        {
            Piece np = new Piece(p.row, p.col, p.color, 0);
            np.type = p.type;
            pieces.add(np);
        }
        previousMove = other.previousMove; // Move is read-only after creation, safe to share
        pieceMoved = (other.pieceMoved == null) ? null
                : new Piece(other.pieceMoved.row, other.pieceMoved.col, other.pieceMoved.color, 0);
        if(pieceMoved != null) pieceMoved.type = other.pieceMoved.type;
        whiteCastle = other.whiteCastle.clone();
        blackCastle = other.blackCastle.clone();
    }

    public Board(String myColor)
    {
        width = 8;
        height = 8;
        this.myColor = myColor;
        color = myColor;
        int[][] board = new int[][]
            {
                {-4,-2,-3,-5,-6,-3,-2,-4},
                {-1,-1,-1,-1,-1,-1,-1,-1},
                { 0, 0, 0, 0, 0, 0, 0, 0},
                { 0, 0, 0, 0, 0, 0, 0, 0},
                { 0, 0, 0, 0, 0, 0, 0, 0},
                { 0, 0, 0, 0, 0, 0, 0, 0},
                { 1, 1, 1, 1, 1, 1, 1, 1},
                { 4, 2, 3, 5, 6, 3, 2, 4},
            };
        pieces = new ArrayList<>();
        for(int r = 0; r < width; r++)
        {
            for(int c = 0; c < height; c++)
            {
                if(color.equals("white"))
                {
                    if(board[r][c] > 0)
                        pieces.add(new Piece(r, c, "white", board[r][c]));
                    else if(board[r][c] < 0)
                        pieces.add(new Piece(r, c, "black", -board[r][c]));
                }
                else if(color.equals("black"))
                {
                    if(board[r][c] > 0)
                        pieces.add(new Piece(7 - r, 7 - c, "white", board[r][c]));
                    else if(board[r][c] < 0)
                        pieces.add(new Piece(7 - r, 7 - c, "black", -board[r][c]));
                }
            }
        }
        previousMove = null;
        pieceMoved = null;
        whiteCastle = new boolean[] {true, true};
        blackCastle = new boolean[] {true, true};
    }

    public ArrayList<Move> getMoves()
    {
        ArrayList<Move> moves = new ArrayList<>();
        for(Piece p: pieces)
        {
            if(p.color.equals(color) && p.type.equals("pawn"))
            {
                if(isValid(p.row - 1, p.col) && getPiece(p.row - 1, p.col) == null)
                    moves.add(new Move(p.row, p.col, p.row - 1, p.col));
                if(p.row == 6 && isValid(p.row - 2, p.col) && getPiece(p.row - 1, p.col) == null && getPiece(p.row - 2, p.col) == null)
                    moves.add(new Move(p.row, p.col, p.row - 2, p.col));
                if(isValid(p.row - 1, p.col - 1) && getPiece(p.row - 1, p.col - 1) != null && !getPiece(p.row - 1, p.col - 1).color.equals(color))
                    moves.add(new Move(p.row, p.col, p.row - 1, p.col - 1));
                if(isValid(p.row - 1, p.col + 1) && getPiece(p.row - 1, p.col + 1) != null && !getPiece(p.row - 1, p.col + 1).color.equals(color))
                    moves.add(new Move(p.row, p.col, p.row - 1, p.col + 1));

                // BUG FIX: en passant - previousMove/pieceMoved can be null at game start
                // BUG FIX: en passant column mapping was wrong after board flip.
                // After flipBoard(), the opponent's pawn is at (3, mirroredCol).
                // The opponent moved from row 1 to row 4 (from their perspective: row 6 to row 4).
                // After our flip that's rows 3->6 from our perspective... but pieceMoved is
                // already stored in flipped coords since update() stores it before flipBoard().
                // The previousMove coords are in the opponent's flipped frame.
                // So the captured pawn sits at our row 3, and its col = 7 - previousMove.endCol.
                if(previousMove != null && pieceMoved != null && p.row == 3
                        && pieceMoved.type.equals("pawn")
                        && Math.abs(previousMove.startRow - previousMove.endRow) == 2)
                {
                    int epCol = 7 - previousMove.endCol; // map opponent col to our frame
                    if(epCol == p.col - 1)
                        moves.add(new Move(p.row, p.col, p.row - 1, p.col - 1));
                    if(epCol == p.col + 1)
                        moves.add(new Move(p.row, p.col, p.row - 1, p.col + 1));
                }
            }
            if(p.color.equals(color) && p.type.equals("knight"))
            {
                int[] xMove = {-1, 1, 2, 2, 1, -1, -2, -2};
                int[] yMove = {-2, -2, -1, 1, 2, 2, 1, -1};
                for(int i = 0; i < xMove.length; i++)
                {
                    if(isValid(p.row + yMove[i], p.col + xMove[i]))
                        moves.add(new Move(p.row, p.col, p.row + yMove[i], p.col + xMove[i]));
                }
            }
            if(p.color.equals(color) && (p.type.equals("bishop") || p.type.equals("queen")))
            {
                int r = p.row;
                int c = p.col;
                while(r >= 0 && c >= 0 && (getPiece(r, c) == null || (r == p.row && c == p.col)))
                {
                    r--; c--;
                    if(isValid(r, c))
                        moves.add(new Move(p.row, p.col, r, c));
                    if(r >= 0 && r < 8 && c >= 0 && c < 8 && getPiece(r, c) != null)
                        break;
                }
                r = p.row; c = p.col;
                while(r >= 0 && c < 8 && (getPiece(r, c) == null || (r == p.row && c == p.col)))
                {
                    r--; c++;
                    if(isValid(r, c))
                        moves.add(new Move(p.row, p.col, r, c));
                    if(r >= 0 && r < 8 && c >= 0 && c < 8 && getPiece(r, c) != null)
                        break;
                }
                r = p.row; c = p.col;
                while(r < 8 && c < 8 && (getPiece(r, c) == null || (r == p.row && c == p.col)))
                {
                    r++; c++;
                    if(isValid(r, c))
                        moves.add(new Move(p.row, p.col, r, c));
                    if(r >= 0 && r < 8 && c >= 0 && c < 8 && getPiece(r, c) != null)
                        break;
                }
                r = p.row; c = p.col;
                while(r < 8 && c >= 0 && (getPiece(r, c) == null || (r == p.row && c == p.col)))
                {
                    r++; c--;
                    if(isValid(r, c))
                        moves.add(new Move(p.row, p.col, r, c));
                    if(r >= 0 && r < 8 && c >= 0 && c < 8 && getPiece(r, c) != null)
                        break;
                }
            }
            if(p.color.equals(color) && (p.type.equals("rook") || p.type.equals("queen")))
            {
                int r = p.row;
                int c = p.col;
                while(r >= 0 && (getPiece(r, c) == null || (r == p.row && c == p.col)))
                {
                    r--;
                    if(isValid(r, c))
                        moves.add(new Move(p.row, p.col, r, c));
                    if(r >= 0 && getPiece(r, c) != null)
                        break;
                }
                r = p.row; c = p.col;
                while(c < 8 && (getPiece(r, c) == null || (r == p.row && c == p.col)))
                {
                    c++;
                    if(isValid(r, c))
                        moves.add(new Move(p.row, p.col, r, c));
                    if(c < 8 && getPiece(r, c) != null)
                        break;
                }
                r = p.row; c = p.col;
                while(r < 8 && (getPiece(r, c) == null || (r == p.row && c == p.col)))
                {
                    r++;
                    if(isValid(r, c))
                        moves.add(new Move(p.row, p.col, r, c));
                    if(r < 8 && getPiece(r, c) != null)
                        break;
                }
                r = p.row; c = p.col;
                while(c >= 0 && (getPiece(r, c) == null || (r == p.row && c == p.col)))
                {
                    c--;
                    if(isValid(r, c))
                        moves.add(new Move(p.row, p.col, r, c));
                    if(c >= 0 && getPiece(r, c) != null)
                        break;
                }
            }
            if(p.color.equals(color) && p.type.equals("king"))
            {
                int[] xMove = {0, 1, 1, 1, 0, -1, -1, -1};
                int[] yMove = {-1, -1, 0, 1, 1, 1, 0, -1};
                for(int i = 0; i < xMove.length; i++)
                {
                    if(isValid(p.row + yMove[i], p.col + xMove[i]))
                        moves.add(new Move(p.row, p.col, p.row + yMove[i], p.col + xMove[i]));
                }
            }
        }
        return moves;
    }

    public void addCastleMove(ArrayList<Move> moves)
    {
        for(Piece p: pieces)
        {
            if(p.color.equals(color) && p.type.equals("king"))
            {
                if(color.equals("white") && !inCheck() && !squareAttacked(p.row, p.col - 1) && !squareAttacked(p.row, p.col - 2) && !squareAttacked(p.row, p.col - 3) && whiteCastle[0] && getPiece(p.row, p.col - 1) == null && getPiece(p.row, p.col - 2) == null && getPiece(p.row, p.col - 3) == null)
                    moves.add(new Move(p.row, p.col, p.row, p.col - 2));
                if(color.equals("white") && !inCheck() && !squareAttacked(p.row, p.col + 1) && !squareAttacked(p.row, p.col + 2) && whiteCastle[1] && getPiece(p.row, p.col + 1) == null && getPiece(p.row, p.col + 2) == null)
                    moves.add(new Move(p.row, p.col, p.row, p.col + 2));
                if(color.equals("black") && !inCheck() && !squareAttacked(p.row, p.col - 1) && !squareAttacked(p.row, p.col - 2) && blackCastle[0] && getPiece(p.row, p.col - 1) == null && getPiece(p.row, p.col - 2) == null)
                    moves.add(new Move(p.row, p.col, p.row, p.col - 2));
                if(color.equals("black") && !inCheck() && !squareAttacked(p.row, p.col + 1) && !squareAttacked(p.row, p.col + 2) && !squareAttacked(p.row, p.col + 3) && blackCastle[1] && getPiece(p.row, p.col + 1) == null && getPiece(p.row, p.col + 2) == null && getPiece(p.row, p.col + 3) == null)
                    moves.add(new Move(p.row, p.col, p.row, p.col + 2));
            }
        }
    }

    // BUG FIX: The original undo in removeInCheckMoves was broken for special moves
    // (castling moved a rook that wasn't undone; en passant removed a pawn that wasn't restored).
    // We now save and restore the full pieces list so the undo is always perfect.
    public void removeInCheckMoves(ArrayList<Move> moves)
    {
        for(int i = 0; i < moves.size(); i++)
        {
            Move move = moves.get(i);

            // Snapshot state before the trial move
            ArrayList<Piece> savedPieces = deepCopyPieces();
            Move savedPreviousMove = previousMove;
            Piece savedPieceMoved = pieceMoved;
            boolean[] savedWhiteCastle = whiteCastle.clone();
            boolean[] savedBlackCastle = blackCastle.clone();

            // Apply the move (including special-move side effects via update())
            move(move);
            update(move);

            boolean leavesInCheck = inCheck();

            // Restore everything
            pieces = savedPieces;
            previousMove = savedPreviousMove;
            pieceMoved = savedPieceMoved;
            whiteCastle = savedWhiteCastle;
            blackCastle = savedBlackCastle;

            if(leavesInCheck)
            {
                moves.remove(i);
                i--;
            }
        }
    }

    private ArrayList<Piece> deepCopyPieces()
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

    ArrayList<Move> getMovesFromLocation(int row, int col, ArrayList<Move> moves)
    {
        ArrayList<Move> movesFromLocation = new ArrayList<>();
        for(Move move: moves)
        {
            if(move.startRow == row && move.startCol == col)
                movesFromLocation.add(move);
        }
        return movesFromLocation;
    }

    public boolean isValid(int row, int col)
    {
        if(row < 0 || row > 7 || col < 0 || col > 7)
            return false;
        Piece p = getPiece(row, col);
        return p == null || !p.color.equals(color);
    }

    public boolean inCheck()
    {
        Piece king = null;
        for(Piece p: pieces)
        {
            if(p.type.equals("king") && p.color.equals(color))
                king = p;
        }
        if(king == null) return false;
        return squareAttacked(king.row, king.col);
    }

    public boolean squareAttacked(int r, int c)
    {
        flipBoard();
        ArrayList<Move> moves = getMoves();
        for(Move move: moves)
        {
            if(move.endRow == 7 - r && move.endCol == 7 - c)
            {
                flipBoard();
                return true;
            }
        }
        flipBoard();
        return false;
    }

    public boolean isDead()
    {
        if(pieces.size() == 2)
            return true;
        if(pieces.size() == 3)
        {
            for(Piece p: pieces)
            {
                if(p.type.equals("knight") || p.type.equals("bishop"))
                    return true;
            }
        }
        if(pieces.size() == 4)
        {
            Piece whiteBishop = null;
            Piece blackBishop = null;
            for(Piece p: pieces)
            {
                if(p.type.equals("bishop") && p.color.equals("white"))
                    whiteBishop = p;
                else if(p.type.equals("bishop") && p.color.equals("black"))
                    blackBishop = p;
            }
            if(whiteBishop == null || blackBishop == null)
                return false;
            return (whiteBishop.row + whiteBishop.col) % 2 == (blackBishop.row + blackBishop.col) % 2;
        }
        return false;
    }

    public Piece getPiece(int r, int c)
    {
        for(Piece p: pieces)
        {
            if(p.row == r && p.col == c)
                return p;
        }
        return null;
    }

    public void removePiece(int r, int c)
    {
        for(Piece p: pieces)
        {
            if(p.row == r && p.col == c)
            {
                pieces.remove(p);
                return;
            }
        }
    }

    public void move(Move m)
    {
        if(getPiece(m.endRow, m.endCol) != null)
            removePiece(m.endRow, m.endCol);
        Piece p = getPiece(m.startRow, m.startCol);
        if(p != null)
            p.move(m.endRow, m.endCol);
    }

    public void update(Move move)
    {
        // En passant captures
        if(getPiece(move.endRow, move.endCol).type.equals("pawn")
                && move.startRow == 3 && move.startCol != move.endCol
                && pieceMoved != null && pieceMoved.type.equals("pawn")
                && previousMove != null
                && previousMove.endRow - previousMove.startRow == -2
                && 7 - previousMove.endCol == move.startCol - 1)
            removePiece(move.startRow, move.endCol);

        if(getPiece(move.endRow, move.endCol).type.equals("pawn")
                && move.startRow == 3 && move.startCol != move.endCol
                && pieceMoved != null && pieceMoved.type.equals("pawn")
                && previousMove != null
                && previousMove.endRow - previousMove.startRow == -2
                && 7 - previousMove.endCol == move.startCol + 1)
            removePiece(move.startRow, move.endCol);

        // Pawn promotion — handled externally for the human player;
        // for AI moves the caller sets the type before calling update,
        // so we only auto-promote here as a fallback (AI path).
        if(getPiece(move.endRow, move.endCol) != null
                && getPiece(move.endRow, move.endCol).type.equals("pawn")
                && move.endRow == 0)
            getPiece(move.endRow, move.endCol).type = "queen";

        // Castling rook moves
        if(color.equals("white") && getPiece(move.endRow, move.endCol).type.equals("king") && move.endCol - move.startCol == -2)
            move(new Move(7, 0, 7, 3));
        if(color.equals("white") && getPiece(move.endRow, move.endCol).type.equals("king") && move.endCol - move.startCol == 2)
            move(new Move(7, 7, 7, 5));
        if(color.equals("black") && getPiece(move.endRow, move.endCol).type.equals("king") && move.endCol - move.startCol == -2)
            move(new Move(7, 0, 7, 2));
        if(color.equals("black") && getPiece(move.endRow, move.endCol).type.equals("king") && move.endCol - move.startCol == 2)
            move(new Move(7, 7, 7, 4));

        // Castling rights
        if(color.equals("white") && (getPiece(move.endRow, move.endCol).type.equals("king") || (getPiece(move.endRow, move.endCol).type.equals("rook") && move.startCol == 0)))
            whiteCastle[0] = false;
        if(color.equals("white") && (getPiece(move.endRow, move.endCol).type.equals("king") || (getPiece(move.endRow, move.endCol).type.equals("rook") && move.startCol == 7)))
            whiteCastle[1] = false;
        if(color.equals("black") && (getPiece(move.endRow, move.endCol).type.equals("king") || (getPiece(move.endRow, move.endCol).type.equals("rook") && move.startCol == 0)))
            blackCastle[0] = false;
        if(color.equals("black") && (getPiece(move.endRow, move.endCol).type.equals("king") || (getPiece(move.endRow, move.endCol).type.equals("rook") && move.startCol == 7)))
            blackCastle[1] = false;

        previousMove = move;
        pieceMoved = getPiece(move.endRow, move.endCol);
    }

    public void flipBoard()
    {
        for(Piece p: pieces)
        {
            p.row = 7 - p.row;
            p.col = 7 - p.col;
        }
        if(color.equals("white"))
            color = "black";
        else if(color.equals("black"))
            color = "white";
    }
}
