public class Move
{
    public int startRow;
    public int startCol;
    public int endRow;
    public int endCol;
    
    public Move(int sr, int sc, int er, int ec)
    {
        startRow = sr;
        startCol = sc;
        endRow = er;
        endCol = ec;
    }
    
    public String toString()
    {
        return "(" + startRow + "," + startCol + ")->(" + endRow + "," + endCol + ")";
    }
}