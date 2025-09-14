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
        switch(value)
        {
            case 1:
            type = "pawn";
            break;
            case 2:
            type = "knight";
            break;
            case 3:
            type = "bishop";
            break;
            case 4:
            type = "rook";
            break;
            case 5:
            type = "queen";
            break;
            case 6:
            type = "king";
            break;
            default:
            type = "";
            break;
        }
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

    public String toString()
    {
        return row + " " + col + " " + color + " " + type;
    }
}