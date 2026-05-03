public class Piece
{
    public int row;
    public int col;
    public String color;
    public String type;

    public Piece(int row, int col, String color, int value)
    {
        this.row = row;
        this.col = col;
        this.color = color;
        type = switch (value) {
            case 1 -> "pawn";
            case 2 -> "knight";
            case 3 -> "bishop";
            case 4 -> "rook";
            case 5 -> "queen";
            case 6 -> "king";
            default -> "";
        };
    }

    public Piece(int row, int col)
    {
        this.row = row;
        this.col = col;
    }

    public void move(int r, int c)
    {
        row = r;
        col = c;
    }

    public boolean equals(Piece p)
    {
        return row == p.row && col == p.col;
    }

    @Override
    public String toString()
    {
        return row + " " + col + " " + color + " " + type;
    }
}