import java.awt.*;
import java.awt.image.*;
import java.awt.event.*;
import javax.swing.*;
import java.util.*;
import java.io.*;

public class Chess extends JFrame implements Runnable, MouseListener, MouseMotionListener
{  
    private static final long serialVersionUID = 1L;
    private int width;
    private int height;

    private int frame;
    private Thread thread;
    private boolean running;
    private BufferedImage image;
    private int[] pixels;

    private String myColor;

    private String turn;
    private int turnsSincePawnMoveOrCapture;

    private Board board;
    private ArrayList<Move> moves;

    private int selectedRow;
    private int selectedCol;
    private ArrayList<Move> selectedLocationMoves;

    private boolean gameOver;

    private int whitePieceColor;
    private int blackPieceColor;
    private int lightBackgroundColor;
    private int darkBackgroundColor;

    private int[][] pawnTexture;
    private int[][] knightTexture;
    private int[][] bishopTexture;
    private int[][] rookTexture;
    private int[][] queenTexture;
    private int[][] kingTexture;

    private int mouseX;
    private int mouseY;
    private boolean mousePressed;
    private boolean mouseClicked;

    public Chess()
    {
        //set size of screen
        width = 512;
        height = 512;

        //set initial frame
        frame = 0;

        //what will be displayed to the user
        thread = new Thread(this);
        image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        pixels = ((DataBufferInt)image.getRaster().getDataBuffer()).getData();

        do
        {
            myColor = JOptionPane.showInputDialog("Do you want to play as white or black?").toLowerCase();
        }while(!myColor.equals("white") && !myColor.equals("black"));

        turn = "white";
        turnsSincePawnMoveOrCapture = 0;

        board = new Board(myColor);
        if(myColor.equals("black"))
            board.flipBoard();

        moves = new ArrayList<Move>();

        selectedRow = -1;
        selectedCol = -1;
        selectedLocationMoves = new ArrayList<Move>();

        gameOver = false;

        whitePieceColor = RGB(255, 255, 255);
        blackPieceColor = RGB(0, 0,0);
        lightBackgroundColor = RGB(160, 160, 160);
        darkBackgroundColor = RGB(96, 96, 96);

        pawnTexture = new int[64][64];
        readFile(pawnTexture, "src/SavedTextures/Pawn.txt");
        knightTexture = new int[64][64];
        readFile(knightTexture, "src/SavedTextures/Knight.txt");
        bishopTexture = new int[64][64];
        readFile(bishopTexture, "src/SavedTextures/Bishop.txt");
        rookTexture = new int[64][64];
        readFile(rookTexture, "src/SavedTextures/Rook.txt");
        queenTexture = new int[64][64];
        readFile(queenTexture, "src/SavedTextures/Queen.txt");
        kingTexture = new int[64][64];
        readFile(kingTexture, "src/SavedTextures/King.txt");

        //mouse input
        addMouseListener(this);
        addMouseMotionListener(this);

        //setting up the window
        setSize(width + 16, height + 39);
        setResizable(false);
        setTitle("Chess");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setVisible(true);

        //start the program
        start();
    }

    private synchronized void start()
    {
        //starts game
        running = true;
        thread.start();
    }

    private synchronized void stop()
    {
        //stops game
        running = false;
        try
        {
            thread.join();
        }
        catch(InterruptedException e)
        {
            e.printStackTrace();
        }
    }

    private void update()
    {
        //updates everything
        for(int y = 0; y < height; y++)
        {
            for(int x = 0; x < width; x++)
            {
                if(x % (width / board.width * 2) < width / board.width && y % (height / board.height * 2) >= height / board.height)
                    pixels[y * width + x] = darkBackgroundColor;
                else if(x % (width / board.width * 2) >= width / board.width && y % (height / board.height * 2) < height / board.height)
                    pixels[y * width + x] = darkBackgroundColor;
                else
                    pixels[y * width + x] = lightBackgroundColor;
            }
        }
        if(!turn.equals(myColor))
            board.flipBoard();
        for(int y = 0; y < height; y++)
        {
            for(int x = 0; x < width; x++)
            {
                if(x % (width / board.width) == 0 && y % (height / board.height) == 0 && board.getPiece(y * board.height / height, x * board.width / width) != null)
                    drawTexture(board.getPiece(y * board.height / height, x * board.width / width).type, board.getPiece(y * board.height / height, x * board.width / width).color, x, y, width / board.width);
            }
        }
        if(!turn.equals(myColor))
            board.flipBoard();
        moves = board.getMoves();
        board.addCastleMove(moves);
        board.removeInCheckMoves(moves);
        if(!gameOver && moves.isEmpty() && board.inCheck())
        {
            if(turn.equals("white"))
                System.out.println("Checkmate, black wins");
            else if(turn.equals("black"))
                System.out.println("Checkmate, white wins");
            gameOver = true;
        }
        if(!gameOver && moves.isEmpty())
        {
            System.out.println("Draw, stalemate");
            gameOver = true;
        }
        if(!gameOver && board.isDead())
        {
            System.out.println("Draw, dead board");
            gameOver = true;
        }
        if(!gameOver && turnsSincePawnMoveOrCapture >= 100)
        {
            System.out.println("Draw, fifty-move rule");
            gameOver = true;
        }
        if(!gameOver)
        {
            if(turn.equals(myColor))
            {
                selectedLocationMoves.clear();
                if(selectedRow != -1 && selectedCol != -1)
                    selectedLocationMoves = board.getMovesFromLocation(selectedRow, selectedCol, moves);
                if(mouseClicked)
                {
                    Move m = null;
                    for(Move move: selectedLocationMoves)
                    {
                        if((mouseY - 26) * board.height / height == move.endRow && (mouseX - 3) * board.width / width == move.endCol)
                        {
                            m = move;
                            break;
                        }
                    }
                    if(m != null)
                        makeMove(m);
                    else
                    {
                        selectedRow = (mouseY - 26) * board.height / height;
                        selectedCol = (mouseX - 3) * board.width / width;
                    }
                }
            }
            else
            {
                //generate random move for computer
                Move m = moves.get((int) (Math.random() * moves.size()));
                makeMove(m);
                if(mouseClicked)
                {
                    selectedRow = (mouseY - 26) * board.height / height;
                    selectedCol = (mouseX - 3) * board.width / width;
                }
            }
        }
        if(mousePressed)
            mousePressed = false;
        if(mouseClicked)
            mouseClicked = false;
    }

    private void render()
    {
        //sets up graphics
        BufferStrategy bs = getBufferStrategy();
        if(bs == null)
        {
            createBufferStrategy(3);
            return;
        }
        Graphics g = bs.getDrawGraphics();

        //draws pixel array to screen
        g.drawImage(image, 8, 31, image.getWidth(), image.getHeight(), null);

        //draw boxes around selected square and possible moves
        g.translate(8, 31);
        if(selectedRow != -1 && selectedCol != -1)
        {
            if(turn.equals(myColor))
            {
                g.setColor(new Color(0, 255, 0));
                for(Move move: selectedLocationMoves)
                {
                    g.drawRect(move.endCol * width / board.width - 2, move.endRow * height / board.height - 2, width / board.width + 4, height / board.height + 4);
                    g.drawRect(move.endCol * width / board.width - 1, move.endRow * height / board.height - 1, width / board.width + 2, height / board.height + 2);
                    g.drawRect(move.endCol * width / board.width, move.endRow * height / board.height, width / board.width, height / board.height);
                    g.drawRect(move.endCol * width / board.width + 1, move.endRow * height / board.height + 1, width / board.width - 2, height / board.height - 2);
                    g.drawRect(move.endCol * width / board.width + 2, move.endRow * height / board.height + 2, width / board.width - 4, height / board.height - 4);
                }
            }
            g.setColor(new Color(255, 255, 0));
            g.drawRect(selectedCol * width / board.width - 2, selectedRow * height / board.height - 2, width / board.width + 4, height / board.height + 4);
            g.drawRect(selectedCol * width / board.width - 1, selectedRow * height / board.height - 1, width / board.width + 2, height / board.height + 2);
            g.drawRect(selectedCol * width / board.width, selectedRow * height / board.height, width / board.width, height / board.height);
            g.drawRect(selectedCol * width / board.width + 1, selectedRow * height / board.height + 1, width / board.width - 2, height / board.height - 2);
            g.drawRect(selectedCol * width / board.width + 2, selectedRow * height / board.height + 2, width / board.width - 4, height / board.height - 4);
        }

        //display all the graphics
        bs.show();
    }

    public void run()
    {
        //main game loop
        long lastTime = System.nanoTime();
        final double ns = 1000000000.0 / 60.0; //60 times per second
        double delta = 0;
        requestFocus();
        while(running)
        {
            //updates time
            long now = System.nanoTime();
            delta += (now - lastTime) / ns;
            lastTime = now;
            frame++;
            while(delta >= 1) //Make sure update is only happening 60 times a second
            {
                //update
                update();
                delta--;
            }
            //display to the screen
            render();
        }
    }

    public void readFile(int[][] array, String fileLoc)
    {
        try
        {
            Scanner file = new Scanner(new File(fileLoc));
            for(int y = 0; y < 64; y++)
            {
                for(int x = 0; x < 64; x++)
                {
                    array[y][x] = file.nextInt();
                }
            }
        }
        catch(IOException e)
        {

        }
    }

    private void drawTexture(String texture, String color, int xStart, int yStart, int size)
    {
        for(int y = yStart; y < yStart + size; y++)
        {
            for(int x = xStart; x < xStart + size; x++)
            {
                if(texture.equals("pawn") && pawnTexture[y - yStart][x - xStart] > 0)
                {
                    if(color.equals("white"))
                        pixels[y * width + x] = whitePieceColor;
                    else
                        pixels[y * width + x] = blackPieceColor;
                }
                else if(texture.equals("knight") && knightTexture[y - yStart][x - xStart] > 0)
                {
                    if(color.equals("white"))
                        pixels[y * width + x] = whitePieceColor;
                    else
                        pixels[y * width + x] = blackPieceColor;
                }
                else if(texture.equals("bishop") && bishopTexture[y - yStart][x - xStart] > 0)
                {
                    if(color.equals("white"))
                        pixels[y * width + x] = whitePieceColor;
                    else
                        pixels[y * width + x] = blackPieceColor;
                }
                else if(texture.equals("rook") && rookTexture[y - yStart][x - xStart] > 0)
                {
                    if(color.equals("white"))
                        pixels[y * width + x] = whitePieceColor;
                    else
                        pixels[y * width + x] = blackPieceColor;
                }
                else if(texture.equals("queen") && queenTexture[y - yStart][x - xStart] > 0)
                {
                    if(color.equals("white"))
                        pixels[y * width + x] = whitePieceColor;
                    else
                        pixels[y * width + x] = blackPieceColor;
                }
                else if(texture.equals("king") && kingTexture[y - yStart][x - xStart] > 0)
                {
                    if(color.equals("white"))
                        pixels[y * width + x] = whitePieceColor;
                    else
                        pixels[y * width + x] = blackPieceColor;
                }
            }
        }
    }

    public void makeMove(Move m)
    {
        int numPiecesBeforeMove = board.pieces.size();
        board.move(m);
        board.update(m);
        if(board.getPiece(m.endRow, m.endCol).type.equals("pawn") || numPiecesBeforeMove > board.pieces.size())
            turnsSincePawnMoveOrCapture = 0;
        else
            turnsSincePawnMoveOrCapture++;
        selectedRow = -1;
        selectedCol = -1;
        if(turn.equals("white"))
            turn = "black";
        else if(turn.equals("black"))
            turn = "white";
        board.flipBoard();
    }

    public void mouseClicked(MouseEvent me)
    {
        mousePressed = true;
        mouseClicked = true;
    }

    public void mouseEntered(MouseEvent me)
    {

    }

    public void mouseExited(MouseEvent me)
    {

    }

    public void mousePressed(MouseEvent me)
    {
        mousePressed = true;
    }

    public void mouseReleased(MouseEvent me)
    {
        mousePressed = false;
        mouseClicked = true;
    }

    public void mouseDragged(MouseEvent me)
    {
        mousePressed = true;
        mouseX = me.getX();
        mouseY = me.getY();
    }

    public void mouseMoved(MouseEvent me)
    {
        mousePressed = false;
        mouseX = me.getX();
        mouseY = me.getY();
    }

    private int RGB(int r, int g, int b)
    {
        return r << 16 | g << 8 | b;
    }

    private int R(int color)
    {
        return color >> 16;
    }

    private int G(int color)
    {
        return color >> 8 & 255;
    }

    private int B(int color)
    {
        return color & 255;
    }

    public static void main(String [] args)
    {
        Chess c = new Chess();
    }
}