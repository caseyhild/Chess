import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.io.*;
import java.util.*;
import javax.swing.*;

public class Chess extends JFrame implements Runnable, MouseListener, MouseMotionListener
{
    // -------------------------------------------------------------------------
    // Game state machine
    // -------------------------------------------------------------------------
    private enum GameState { START, PLAYING, GAME_OVER }
    private volatile GameState state = GameState.START;

    // -------------------------------------------------------------------------
    // Window layout — computed dynamically from actual content size each frame.
    // Board is always a square equal to the content height.
    // Panel takes whatever width remains.
    // -------------------------------------------------------------------------
    private static final int DEFAULT_BOARD = 720; // initial board size
    private static final double PANEL_RATIO = 0.28; // panel / total width at default size

    /** Returns the current content-area width (excludes OS window chrome). */
    private int contentW() { return Math.max(1, getWidth()  - getInsets().left - getInsets().right); }
    /** Returns the current content-area height (excludes OS window chrome). */
    private int contentH() { return Math.max(1, getHeight() - getInsets().top  - getInsets().bottom); }

    /** Board pixel size — always equals content height (square). */
    private int boardSize() { return contentH(); }
    /** Side-panel pixel width — whatever is left after the board. */
    private int panelW()    { return Math.max(80, contentW() - boardSize()); }
    /** Total content width. */
    private int totalW()    { return contentW(); }
    /** Total content height. */
    private int totalH()    { return contentH(); }

    private final Thread thread;

    // -------------------------------------------------------------------------
    // Game data
    // -------------------------------------------------------------------------
    private String myColor;
    private String turn;
    private int turnsSincePawnMoveOrCapture;

    private volatile Board board;
    private ArrayList<Move> moves = new ArrayList<>();

    private int selectedRow  = -1;
    private int selectedCol  = -1;
    private ArrayList<Move> selectedLocationMoves = new ArrayList<>();

    private boolean gameOver;
    private String  gameOverMessage = "";

    // -------------------------------------------------------------------------
    // Move history
    // -------------------------------------------------------------------------
    private static class HistoryEntry
    {
        final Board  boardSnap;
        final String label;
        HistoryEntry(Board snap, String label, String turnBefore)
        { this.boardSnap = snap; this.label = label; }
    }

    private final ArrayList<HistoryEntry> history = new ArrayList<>();
    private int viewIndex = -1;

    private Rectangle btnPrev;
    private Rectangle btnNext;

    private final java.util.concurrent.ConcurrentHashMap<Integer, Rectangle> moveClickAreas
            = new java.util.concurrent.ConcurrentHashMap<>();

    private boolean showGameOverOverlay = true;
    private int     moveListScrollOffset = 0;

    private Move     pendingPromotionMove = null;
    private final Rectangle[] promotionRects = new Rectangle[4];
    private final String[]    promotionTypes = {"queen", "rook", "bishop", "knight"};

    // -------------------------------------------------------------------------
    // Move animation
    // -------------------------------------------------------------------------
    private static final long ANIM_DURATION_MS = 220;

    private Move   animMove       = null;  // move currently being animated
    private String animPieceType  = null;  // type of the sliding piece
    private String animPieceColor = null;  // color of the sliding piece
    private long   animStartTime  = 0;     // when the animation started
    // During animation the board has the piece removed from start but not yet at end.
    // We draw it at the interpolated pixel position on top.

    // -------------------------------------------------------------------------
    // Colors
    // -------------------------------------------------------------------------
    private final int whitePieceColor;
    private final int blackPieceColor;
    private final int lightBackgroundColor;
    private final int darkBackgroundColor;

    // -------------------------------------------------------------------------
    // Piece textures
    // -------------------------------------------------------------------------
    private final int[][] pawnTexture;
    private final int[][] knightTexture;
    private final int[][] bishopTexture;
    private final int[][] rookTexture;
    private final int[][] queenTexture;
    private final int[][] kingTexture;

    // -------------------------------------------------------------------------
    // Input
    // -------------------------------------------------------------------------
    private int     mouseX;
    private int     mouseY;
    private boolean mousePressed;
    private boolean mouseClicked;

    // -------------------------------------------------------------------------
    // AI
    // -------------------------------------------------------------------------
    private final AI ai;
    private volatile Move    pendingAIMove    = null;
    private volatile Move    pendingPlayerMove = null;  // set on EDT, consumed on game loop thread
    private volatile boolean aiThinking       = false;

    // -------------------------------------------------------------------------
    // Threefold repetition
    // -------------------------------------------------------------------------
    private final HashMap<String, Integer> positionHistory = new HashMap<>();

    // -------------------------------------------------------------------------
    // UI button rects
    // -------------------------------------------------------------------------
    private Rectangle btnWhite;
    private Rectangle btnBlack;
    private Rectangle btnRandom;
    private Rectangle btnPlayAgain;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------
    @SuppressWarnings({"ThisEscapedInObjectConstruction", "CallToThreadStartDuringObjectConstruction"})
    public Chess()
    {
        thread = new Thread(this);
        thread.setDaemon(true);

        whitePieceColor      = RGB(255, 255, 255);
        blackPieceColor      = RGB(0,   0,   0  );
        lightBackgroundColor = RGB(160, 160, 160);
        darkBackgroundColor  = RGB(96,  96,  96 );

        pawnTexture   = new int[64][64];  readFile(pawnTexture,   "saved-textures/pawn.txt");
        knightTexture = new int[64][64];  readFile(knightTexture, "saved-textures/knight.txt");
        bishopTexture = new int[64][64];  readFile(bishopTexture, "saved-textures/bishop.txt");
        rookTexture   = new int[64][64];  readFile(rookTexture,   "saved-textures/rook.txt");
        queenTexture  = new int[64][64];  readFile(queenTexture,  "saved-textures/queen.txt");
        kingTexture   = new int[64][64];  readFile(kingTexture,   "saved-textures/king.txt");

        ai = new AI();

        // Aspect ratio snap on resize-end.
        // Track size at drag-start so we can tell which dimension changed more.
        final int[] sizeAtDragStart = { getWidth(), getHeight() };
        final javax.swing.Timer[] snapTimer = { null };
        addComponentListener(new ComponentAdapter()
        {
            @Override
            public void componentResized(ComponentEvent e)
            {
                if (snapTimer[0] == null)
                {
                    // First event of this drag — record starting size
                    sizeAtDragStart[0] = getWidth();
                    sizeAtDragStart[1] = getHeight();
                }
                else
                {
                    snapTimer[0].stop();
                }

                snapTimer[0] = new javax.swing.Timer(150, ev -> {
                    snapTimer[0].stop();
                    snapTimer[0] = null;

                    Insets ins = getInsets();
                    int chromeW = ins.left + ins.right;
                    int chromeH = ins.top  + ins.bottom;

                    int dw = Math.abs(getWidth()  - sizeAtDragStart[0]);
                    int dh = Math.abs(getHeight() - sizeAtDragStart[1]);

                    int targetW, targetH;
                    if (dh >= dw)
                    {
                        // Height changed more — height drives
                        int boardH  = Math.max(300, getHeight() - chromeH);
                        int panelPx = Math.max(80, (int)(boardH * PANEL_RATIO));
                        targetW = boardH + panelPx + chromeW;
                        targetH = boardH + chromeH;
                    }
                    else
                    {
                        // Width changed more — width drives
                        int contentW = getWidth() - chromeW;
                        int boardH   = Math.max(300, (int)(contentW / (1.0 + PANEL_RATIO)));
                        int panelPx  = Math.max(80, (int)(boardH * PANEL_RATIO));
                        targetW = boardH + panelPx + chromeW;
                        targetH = boardH + chromeH;
                    }

                    // Ensure the window stays fully on screen — shift position if needed
                    // rather than shrinking the window.
                    Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                            .getMaximumWindowBounds();

                    int newX = getX(), newY = getY();

                    // If window extends below screen, shift it up
                    if (newY + targetH > screen.y + screen.height)
                        newY = screen.y + screen.height - targetH;
                    // If window extends past right edge, shift it left
                    if (newX + targetW > screen.x + screen.width)
                        newX = screen.x + screen.width - targetW;
                    // Clamp so window doesn't go above or left of screen
                    newX = Math.max(screen.x, newX);
                    newY = Math.max(screen.y, newY);

                    // If even after shifting it still doesn't fit (window bigger than screen),
                    // shrink to fit as a last resort
                    if (targetH > screen.height)
                    {
                        targetH = screen.height;
                        int boardH  = Math.max(300, targetH - chromeH);
                        int panelPx = Math.max(80, (int)(boardH * PANEL_RATIO));
                        targetW = boardH + panelPx + chromeW;
                        newY = screen.y;
                    }
                    if (targetW > screen.width)
                    {
                        targetW = screen.width;
                        int contentW = targetW - chromeW;
                        int boardH   = Math.max(300, (int)(contentW / (1.0 + PANEL_RATIO)));
                        int panelPx  = Math.max(80, (int)(boardH * PANEL_RATIO));
                        targetW = boardH + panelPx + chromeW;
                        targetH = boardH + chromeH;
                        newX = screen.x;
                    }

                    boolean sizeChanged = Math.abs(getWidth()  - targetW) > 1
                                      || Math.abs(getHeight() - targetH) > 1;
                    boolean posChanged  = newX != getX() || newY != getY();
                    if (sizeChanged || posChanged)
                        setBounds(newX, newY, targetW, targetH);
                });
                snapTimer[0].setRepeats(false);
                snapTimer[0].start();
            }
        });

        int initW = DEFAULT_BOARD + (int)(DEFAULT_BOARD * PANEL_RATIO);
        setSize(initW, DEFAULT_BOARD);  // approximate — corrected after setVisible
        setMinimumSize(new Dimension(380, 300));
        setResizable(true);
        setTitle("Chess");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter()
        {
            @Override public void windowClosing(WindowEvent e) { System.exit(0); }
        });
        setLocationRelativeTo(null);
        setVisible(true);

        // Register listeners after setVisible so 'this' is fully constructed
        addMouseListener(this);
        addMouseMotionListener(this);
        addMouseWheelListener(e -> {
            if (mouseX >= boardSize())
            {
                int totalPairs = Math.max(0, (history.size() - 1 + 1) / 2);
                moveListScrollOffset += e.getWheelRotation();
                moveListScrollOffset = Math.max(0, Math.min(moveListScrollOffset, Math.max(0, totalPairs - 1)));
            }
        });

        // Now insets are known — set the exact correct initial size
        Insets ins = getInsets();
        int chromeW = ins.left + ins.right;
        int chromeH = ins.top  + ins.bottom;
        int panelPx = (int)(DEFAULT_BOARD * PANEL_RATIO);
        setSize(DEFAULT_BOARD + panelPx + chromeW, DEFAULT_BOARD + chromeH);
        setLocationRelativeTo(null);  // re-center after resize
        setMinimumSize(new Dimension(300 + (int)(300 * PANEL_RATIO) + chromeW, 300 + chromeH));

        thread.start();
    }

    // -------------------------------------------------------------------------
    // Game initialisation
    // -------------------------------------------------------------------------
    private void startGame(String color)
    {
        myColor = color;
        turn    = "white";
        turnsSincePawnMoveOrCapture = 0;

        board = new Board(myColor);
        if (myColor.equals("black"))
            board.flipBoard();

        moves                 = new ArrayList<>();
        selectedRow           = -1;
        selectedCol           = -1;
        selectedLocationMoves = new ArrayList<>();
        gameOver              = false;
        gameOverMessage       = "";
        pendingAIMove         = null;
        pendingPlayerMove     = null;
        aiThinking            = false;
        pendingPromotionMove  = null;
        animMove              = null;

        history.clear();
        viewIndex = -1;
        moveClickAreas.clear();
        showGameOverOverlay  = true;
        moveListScrollOffset = 0;

        positionHistory.clear();
        state = GameState.PLAYING;
        positionHistory.put(positionKey(), 1);

        Board initSnap = new Board(board);
        if (!initSnap.color.equals(myColor)) initSnap.flipBoard();
        history.add(new HistoryEntry(initSnap, "", "white"));
        viewIndex = 0;

        mouseClicked = false;
    }

    // -------------------------------------------------------------------------
    // Main loop
    // -------------------------------------------------------------------------
    @Override
    public void run()
    {
        long lastTime = System.nanoTime();
        final double ns = 1_000_000_000.0 / 60.0;
        double delta = 0;
        requestFocus();
        while (true)
        {
            long now = System.nanoTime();
            delta += (now - lastTime) / ns;
            lastTime = now;
            while (delta >= 1)
            {
                if (state == GameState.PLAYING)
                    updateGame();
                delta--;
            }
            render();
        }
    }

    // -------------------------------------------------------------------------
    // Update (game logic)
    // -------------------------------------------------------------------------
    private void updateGame()
    {
        if (board == null) return;

        // Check if a move animation is in progress
        if (animMove != null)
        {
            long elapsed = System.currentTimeMillis() - animStartTime;
            if (elapsed >= ANIM_DURATION_MS)
            {
                // Animation complete — finish applying the move
                Move m = animMove;
                animMove = null;
                finishAnimatedMove(m);
            }
            // Block all input during animation
            if (mouseClicked) mouseClicked = false;
            if (mousePressed) mousePressed = false;
            return;
        }

        if (pendingPromotionMove != null)
        {
            if (mouseClicked) mouseClicked = false;
            if (mousePressed) mousePressed = false;
            return;
        }

        boolean reviewing = !history.isEmpty() && viewIndex < history.size() - 1;
        if (reviewing)
        {
            // Selection while reviewing is handled in handleClick
            if (mouseClicked) mouseClicked = false;
            if (mousePressed) mousePressed = false;
            return;
        }

        moves = board.getMoves();
        board.addCastleMove(moves);
        board.removeInCheckMoves(moves);

        if (!gameOver && moves.isEmpty() && board.inCheck())
        { gameOverMessage = turn.equals("white") ? "Checkmate \u2014 Black wins!" : "Checkmate \u2014 White wins!"; gameOver = true; }
        if (!gameOver && moves.isEmpty())
        { gameOverMessage = "Draw \u2014 Stalemate"; gameOver = true; }
        if (!gameOver && board.isDead())
        { gameOverMessage = "Draw \u2014 Insufficient material"; gameOver = true; }
        if (!gameOver && turnsSincePawnMoveOrCapture >= 100)
        { gameOverMessage = "Draw \u2014 Fifty-move rule"; gameOver = true; }

        if (gameOver)
        {
            state = GameState.GAME_OVER;
            if (mouseClicked) mouseClicked = false;
            if (mousePressed) mousePressed = false;
            return;
        }

        if (turn.equals(myColor))
        {
            selectedLocationMoves.clear();
            if (selectedRow != -1 && selectedCol != -1)
                selectedLocationMoves = board.getMovesFromLocation(selectedRow, selectedCol, moves);

            // Execute any move queued by handleClick on the EDT
            if (pendingPlayerMove != null)
            {
                Move m = pendingPlayerMove;
                pendingPlayerMove = null;
                makeMove(m);
            }
        }
        else
        {
            selectedLocationMoves.clear();
            // Selection during AI turn is handled in handleClick

            if (pendingAIMove != null)
            {
                makeMove(pendingAIMove);
                pendingAIMove = null;
                aiThinking    = false;
            }
            else if (!aiThinking)
            {
                Board aiBoard = new Board(board);
                aiThinking = true;
                Thread aiThread = new Thread(() -> pendingAIMove = ai.getBestMove(aiBoard));
                aiThread.setDaemon(true);
                aiThread.start();
            }
        }

        if (mouseClicked) mouseClicked = false;
        if (mousePressed) mousePressed = false;
    }

    // -------------------------------------------------------------------------
    // Render dispatcher
    // -------------------------------------------------------------------------
    private void render()
    {
        // Recreate buffer strategy if needed (happens after resize)
        BufferStrategy strat = getBufferStrategy();
        if (strat == null) { createBufferStrategy(2); return; }

        do {
            do {
                Graphics g = strat.getDrawGraphics();
                try
                {
                    // Fill the entire window with dark background first to prevent
                    // white flashes from the OS repainting the window during resize
                    g.setColor(new Color(30, 30, 30));
                    g.fillRect(0, 0, getWidth(), getHeight());

                    Insets ins = getInsets();
                    g.translate(ins.left, ins.top);

                    switch (state)
                    {
                        case START -> renderStart(g);
                        case PLAYING -> renderGame(g);
                        case GAME_OVER -> renderGameOver(g);
                    }
                }
                finally { g.dispose(); }
            } while (strat.contentsRestored());

            strat.show();
        } while (strat.contentsLost());
    }

    // -------------------------------------------------------------------------
    // Render: start screen
    // -------------------------------------------------------------------------
    private void renderStart(Graphics g)
    {
        int W = totalW(), H = totalH();

        g.setColor(new Color(30, 30, 30));
        g.fillRect(0, 0, W, H);

        // Title — ~10% of height
        int titleSize = Math.max(24, H / 10);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, titleSize));
        FontMetrics fm = g.getFontMetrics();
        String title = "Chess";
        g.drawString(title, (W - fm.stringWidth(title)) / 2, H * 27 / 100);

        // Subtitle
        int subSize = Math.max(12, H / 28);
        g.setFont(new Font("SansSerif", Font.PLAIN, subSize));
        fm = g.getFontMetrics();
        String sub = "Choose your color to start";
        g.setColor(new Color(180, 180, 180));
        g.drawString(sub, (W - fm.stringWidth(sub)) / 2, H * 37 / 100);

        // Buttons — scale with window
        int bw = Math.max(100, W * 22 / 100);
        int bh = Math.max(36,  H *  8 / 100);
        int gap = Math.max(12, W *  3 / 100);
        int totalBW = bw * 2 + gap;
        int bx1 = (W - totalBW) / 2;
        int bx2 = bx1 + bw + gap;
        int by  = H * 46 / 100;

        btnWhite  = new Rectangle(bx1, by, bw, bh);
        btnBlack  = new Rectangle(bx2, by, bw, bh);
        btnRandom = new Rectangle((W - bw) / 2, by + bh + gap, bw, bh);

        drawButton(g, btnWhite,  "Play as White", new Color(240, 240, 240), Color.BLACK);
        drawButton(g, btnBlack,  "Play as Black", new Color(50,  50,  50 ), Color.WHITE);
        drawButton(g, btnRandom, "Random",        new Color(80,  80,  160), Color.WHITE);
    }

    // -------------------------------------------------------------------------
    // Render: in-game (board + side panel)
    // -------------------------------------------------------------------------
    private void renderGame(Graphics g)
    {
        int bs = boardSize();

        // Checkerboard — use pixel-perfect square bounds to avoid rounding gaps
        for (int row = 0; row < 8; row++)
            for (int col = 0; col < 8; col++)
            {
                boolean dark = (row + col) % 2 == 1;
                g.setColor(dark ? new Color(darkBackgroundColor) : new Color(lightBackgroundColor));
                g.fillRect(sqX(col, bs), sqY(row, bs), sqW(col, bs), sqH(row, bs));
            }

        // Pieces
        boolean reviewing = !history.isEmpty() && viewIndex < history.size() - 1;
        Board drawBoard = reviewing ? history.get(viewIndex).boardSnap : board;

        boolean flipped = false;
        if (!reviewing && !turn.equals(myColor)) { board.flipBoard(); flipped = true; }

        for (int row = 0; row < 8; row++)
            for (int col = 0; col < 8; col++)
            {
                Piece p = drawBoard.getPiece(row, col);
                if (p != null)
                    drawPieceGraphics(g, p.type, p.color, sqX(col, bs), sqY(row, bs), sqW(col, bs));
            }

        if (flipped) board.flipBoard();

        // Animated piece — draw at interpolated position on top of the board
        if (animMove != null && animPieceType != null)
        {
            long elapsed = System.currentTimeMillis() - animStartTime;
            float t = Math.min(1f, (float) elapsed / ANIM_DURATION_MS);
            t = t * t * (3 - 2 * t);

            int sr = animMove.startRow, sc = animMove.startCol;
            int er = animMove.endRow,   ec = animMove.endCol;
            if (!turn.equals(myColor))
            { sr = 7 - sr; sc = 7 - sc; er = 7 - er; ec = 7 - ec; }

            float startX = sqX(sc, bs), startY = sqY(sr, bs);
            float endX   = sqX(ec, bs), endY   = sqY(er, bs);
            int drawX = Math.round(startX + (endX - startX) * t);
            int drawY = Math.round(startY + (endY - startY) * t);
            drawPieceGraphics(g, animPieceType, animPieceColor, drawX, drawY, sqW(sc, bs));
        }

        // Move highlights
        if (selectedRow != -1 && selectedCol != -1)
        {
            // Green move dots — only on the live position during the player's turn
            if (!reviewing && turn.equals(myColor))
            {
                g.setColor(new Color(0, 200, 0, 180));
                for (Move move : selectedLocationMoves)
                {
                    Piece target = board.getPiece(move.endRow, move.endCol);
                    int px = sqX(move.endCol, bs), py = sqY(move.endRow, bs);
                    int sw = sqW(move.endCol, bs), sh = sqH(move.endRow, bs);
                    if (target != null)
                    {
                        int arm = sw / 4;
                        g.fillRect(px,            py,            arm, arm);
                        g.fillRect(px + sw - arm, py,            arm, arm);
                        g.fillRect(px,            py + sh - arm, arm, arm);
                        g.fillRect(px + sw - arm, py + sh - arm, arm, arm);
                    }
                    else
                    {
                        int dot = sw / 4;
                        g.fillOval(px + sw/2 - dot/2, py + sh/2 - dot/2, dot, dot);
                    }
                }
            }
            g.setColor(new Color(255, 220, 0));
            int sx = sqX(selectedCol, bs), sy2 = sqY(selectedRow, bs);
            int sw = sqW(selectedCol, bs), sh = sqH(selectedRow, bs);
            for (int t = 0; t < 3; t++)
                g.drawRect(sx + t, sy2 + t, sw - 2*t, sh - 2*t);
        }

        // "Thinking" overlay
        int bannerH = Math.max(20, bs / 20);
        int bannerFont = Math.max(10, bannerH * 2 / 3);
        if (!reviewing && !turn.equals(myColor) && aiThinking)
        {
            g.setColor(new Color(0, 0, 0, 140));
            g.fillRect(0, 0, bs, bannerH);
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, bannerFont));
            g.drawString("Computer is thinking...", bs / 50, bannerH * 3 / 4);
        }

        // "Reviewing history" banner
        if (reviewing)
        {
            g.setColor(new Color(180, 120, 0, 200));
            g.fillRect(0, 0, bs, bannerH);
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, bannerFont));
            g.drawString("Reviewing history \u2014 click \u25ba to return to live game",
                         bs / 50, bannerH * 3 / 4);
        }

        if (pendingPromotionMove != null)
            renderPromotionPicker(g);

        renderSidePanel(g);
    }

    // -------------------------------------------------------------------------
    // Render: side panel
    // -------------------------------------------------------------------------
    private void renderSidePanel(Graphics g)
    {
        int bs  = boardSize();
        int pw  = panelW();
        int H   = totalH();
        int px  = bs;  // panel x origin

        // Reset font to a neutral size so banner font from renderGame doesn't bleed in
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));

        g.setColor(new Color(28, 28, 28));
        g.fillRect(px, 0, pw, H);
        g.setColor(new Color(70, 70, 70));
        g.drawLine(px, 0, px, H);

        // Header — font sized to fit "Move History" within the available panel width
        int headerH = Math.max(20, H / 26);

        // Small "Play Again" button in header (drawn first so we know its width)
        boolean showHeaderBtn = state == GameState.GAME_OVER
                && (viewIndex < history.size() - 1 || !showGameOverOverlay);
        int nbw = 0;
        if (showHeaderBtn)
        {
            nbw = Math.min(pw * 45 / 100, Math.max(50, pw / 3));
            int nbh = headerH - 4;
            Rectangle btnNewGame = new Rectangle(px + pw - nbw - 4, 2, nbw, nbh);
            drawSmallButton(g, btnNewGame, "Play Again", new Color(60, 160, 60), Color.WHITE);
            btnPlayAgain = btnNewGame;
        }

        // "Move History" label — constrained to not overlap the button
        int textMaxW = pw - nbw - pw / 10 - (showHeaderBtn ? 8 : 0);
        int headerFont = Math.max(8, pw / 10);
        while (headerFont > 8)
        {
            g.setFont(new Font("SansSerif", Font.BOLD, headerFont));
            if (g.getFontMetrics().stringWidth("Move History") <= textMaxW) break;
            headerFont--;
        }
        g.setColor(new Color(200, 200, 200));
        g.drawString("Move History", px + pw / 20, headerH * 3 / 4);

        g.setColor(new Color(70, 70, 70));
        g.drawLine(px, headerH, px + pw, headerH);

        // Nav buttons at the bottom
        int btnH = Math.max(24, H / 16);
        int btnW = (pw - pw * 3 / 20) / 2;
        int btnY = H - btnH - H / 50;
        btnPrev = new Rectangle(px + pw / 20,          btnY, btnW, btnH);
        btnNext = new Rectangle(px + pw / 20 + btnW + pw / 20, btnY, btnW, btnH);

        boolean canPrev = history.size() > 1 && viewIndex > 0;
        boolean canNext = viewIndex < history.size() - 1;

        drawNavButton(g, btnPrev, false, canPrev);
        drawNavButton(g, btnNext, true,  canNext);

        // Move list
        int listTop    = headerH + 4;
        int listBottom = btnY - H / 80;
        int listHeight = listBottom - listTop;

        // Row height: fit as many rows as look good, but cap so rows don't get huge.
        // Target ~20 visible rows at any size; minimum 14px, maximum 28px.
        int rowH = Math.min(28, Math.max(14, listHeight / 20));
        int maxVisible = Math.max(1, listHeight / rowH);

        int totalMoves = history.size() - 1;
        int totalPairs = (totalMoves + 1) / 2;

        // Font size: fit within rowH AND within the column width.
        // Each move column is ~35% of panel width. Longest label ~5 chars ("O-O-O").
        // Start from rowH-based size and shrink if it doesn't fit the column.
        int listColW = pw * 35 / 100;
        int listFont = Math.max(9, rowH * 58 / 100);
        // Verify it fits the column — shrink until "O-O-O" fits
        while (listFont > 9)
        {
            g.setFont(new Font("Monospaced", Font.PLAIN, listFont));
            if (g.getFontMetrics().stringWidth("O-O-O") <= listColW - 8) break;
            listFont--;
        }
        g.setFont(new Font("Monospaced", Font.PLAIN, listFont));
        FontMetrics fm = g.getFontMetrics();
        int ascent = fm.getAscent(), descent = fm.getDescent();
        int boxH = ascent + descent + 4;

        moveClickAreas.clear();

        int totalPairs2 = (history.size() > 1) ? (history.size() / 2) : 0;
        moveListScrollOffset = Math.max(0, Math.min(moveListScrollOffset, Math.max(0, totalPairs2 - maxVisible)));

        // Column layout within panel — proportional
        int numX    = px + pw / 20;
        int whiteX  = px + pw * 18 / 100;
        int blackX  = px + pw * 55 / 100;
        int colW    = pw * 35 / 100;

        for (int pair = moveListScrollOffset; pair < totalPairs && (pair - moveListScrollOffset) < maxVisible; pair++)
        {
            int rowCenterY = listTop + (pair - moveListScrollOffset) * rowH + rowH / 2;
            int drawY  = rowCenterY + (ascent - descent) / 2;
            int boxTop = rowCenterY - boxH / 2;

            g.setColor(new Color(120, 120, 120));
            g.drawString(String.format("%2d.", pair + 1), numX, drawY);

            int whiteHistIdx = pair * 2 + 1;
            if (whiteHistIdx < history.size())
            {
                boolean selected = (whiteHistIdx == viewIndex);
                Rectangle area = new Rectangle(whiteX, boxTop, colW, boxH);
                moveClickAreas.put(whiteHistIdx, area);
                if (selected) { g.setColor(new Color(80, 120, 200, 180)); g.fillRoundRect(area.x, area.y, area.width, area.height, 4, 4); }
                g.setColor(selected ? Color.WHITE : new Color(210, 210, 210));
                g.drawString(history.get(whiteHistIdx).label, whiteX + 4, drawY);
            }

            int blackHistIdx = pair * 2 + 2;
            if (blackHistIdx < history.size())
            {
                boolean selected = (blackHistIdx == viewIndex);
                Rectangle area = new Rectangle(blackX, boxTop, colW, boxH);
                moveClickAreas.put(blackHistIdx, area);
                if (selected) { g.setColor(new Color(80, 120, 200, 180)); g.fillRoundRect(area.x, area.y, area.width, area.height, 4, 4); }
                g.setColor(selected ? Color.WHITE : new Color(160, 160, 160));
                g.drawString(history.get(blackHistIdx).label, blackX + 4, drawY);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Render: game-over screen
    // -------------------------------------------------------------------------
    private void renderGameOver(Graphics g)
    {
        int bs = boardSize();

        for (int row = 0; row < 8; row++)
            for (int col = 0; col < 8; col++)
            {
                boolean dark = (row + col) % 2 == 1;
                g.setColor(dark ? new Color(darkBackgroundColor) : new Color(lightBackgroundColor));
                g.fillRect(sqX(col, bs), sqY(row, bs), sqW(col, bs), sqH(row, bs));
            }

        Board displaySnap = history.isEmpty() ? null : history.get(viewIndex).boardSnap;
        if (displaySnap != null)
            for (int row = 0; row < 8; row++)
                for (int col = 0; col < 8; col++)
                {
                    Piece p = displaySnap.getPiece(row, col);
                    if (p != null) drawPieceGraphics(g, p.type, p.color, sqX(col, bs), sqY(row, bs), sqW(col, bs));
                }

        // Yellow selection highlight
        if (selectedRow != -1 && selectedCol != -1)
        {
            g.setColor(new Color(255, 220, 0));
            int sx = sqX(selectedCol, bs), sy = sqY(selectedRow, bs);
            int sw = sqW(selectedCol, bs), sh = sqH(selectedRow, bs);
            for (int t = 0; t < 3; t++)
                g.drawRect(sx + t, sy + t, sw - 2*t, sh - 2*t);
        }

        renderSidePanel(g);

        boolean atEnd = viewIndex == history.size() - 1;
        if (atEnd && showGameOverOverlay)
        {
            g.setColor(new Color(0, 0, 0, 170));
            g.fillRect(0, 0, bs, bs);

            int msgFont = Math.max(16, bs / 20);
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, msgFont));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(gameOverMessage,
                         (bs - fm.stringWidth(gameOverMessage)) / 2,
                         bs / 2 - bs / 14);

            int bw = bs * 35 / 100, bh = Math.max(36, bs / 12);
            btnPlayAgain = new Rectangle((bs - bw) / 2, bs / 2 + bs / 30, bw, bh);
            drawButton(g, btnPlayAgain, "Play Again", new Color(60, 160, 60), Color.WHITE);
        }
        else
        {
            int bannerH = Math.max(20, bs / 20);
            int bannerFont = Math.max(10, bannerH * 2 / 3);
            g.setColor(new Color(180, 120, 0, 200));
            g.fillRect(0, 0, bs, bannerH);
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, bannerFont));
            g.drawString("Reviewing history \u2014 click \u25ba to return to result",
                         bs / 50, bannerH * 3 / 4);
        }
    }

    // -------------------------------------------------------------------------
    // Render: promotion picker
    // -------------------------------------------------------------------------
    private void renderPromotionPicker(Graphics g)
    {
        int bs  = boardSize();
        int col = pendingPromotionMove.endCol;
        int x   = sqX(col, bs);
        int sw  = sqW(col, bs);

        g.setColor(new Color(0, 0, 0, 140));
        g.fillRect(0, 0, bs, bs);

        for (int i = 0; i < 4; i++)
        {
            int y  = sqY(i, bs);
            int sh = sqH(i, bs);
            promotionRects[i] = new Rectangle(x, y, sw, sh);
            boolean hovered = promotionRects[i].contains(mouseX, mouseY);
            g.setColor(hovered ? new Color(100, 140, 220) : new Color(60, 90, 160));
            g.fillRect(x, y, sw, sh);
            g.setColor(new Color(255, 255, 255, 60));
            g.drawRect(x, y, sw, sh);
            drawPieceGraphics(g, promotionTypes[i], myColor, x, y, sw);
        }
    }

    // -------------------------------------------------------------------------
    // Button helpers
    // -------------------------------------------------------------------------
    private void drawButton(Graphics g, Rectangle r, String label, Color bg, Color fg)
    {
        boolean hovered = r.contains(mouseX, mouseY);
        Color fill = hovered ? bg.darker() : bg;
        int arc = Math.max(6, r.height / 4);

        g.setColor(new Color(0, 0, 0, 80));
        g.fillRoundRect(r.x + 2, r.y + 2, r.width, r.height, arc, arc);
        g.setColor(fill);
        g.fillRoundRect(r.x, r.y, r.width, r.height, arc, arc);
        g.setColor(fg.equals(Color.WHITE) ? new Color(255,255,255,60) : new Color(0,0,0,60));
        g.drawRoundRect(r.x, r.y, r.width, r.height, arc, arc);
        g.setColor(fg);
        int fontSize = Math.max(10, r.height / 2);
        g.setFont(new Font("SansSerif", Font.BOLD, fontSize));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(label,
                     r.x + (r.width  - fm.stringWidth(label)) / 2,
                     r.y + (r.height + fm.getAscent() - fm.getDescent()) / 2);
    }

    private void drawSmallButton(Graphics g, Rectangle r, String label, Color bg, Color fg)
    {
        boolean hovered = r.contains(mouseX, mouseY);
        g.setColor(hovered ? bg.darker() : bg);
        g.fillRoundRect(r.x, r.y, r.width, r.height, 4, 4);
        g.setColor(fg.equals(Color.WHITE) ? new Color(255,255,255,50) : new Color(0,0,0,50));
        g.drawRoundRect(r.x, r.y, r.width, r.height, 4, 4);
        g.setColor(fg);
        int fontSize = Math.max(8, r.height / 2);
        g.setFont(new Font("SansSerif", Font.BOLD, fontSize));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(label,
                     r.x + (r.width  - fm.stringWidth(label)) / 2,
                     r.y + (r.height + fm.getAscent() - fm.getDescent()) / 2);
    }

    // -------------------------------------------------------------------------
    // Nav button helper — draws a prev (◀) or next (▶) button with a
    // pixel-drawn triangle so both arrows are exactly the same size.
    // -------------------------------------------------------------------------
    private void drawNavButton(Graphics g, Rectangle r, boolean pointRight, boolean active)
    {
        Color bg   = active ? new Color(60, 60, 80) : new Color(40, 40, 40);
        Color fg   = active ? Color.WHITE            : new Color(90, 90, 90);
        boolean hovered = active && r.contains(mouseX, mouseY);
        Color fill = hovered ? bg.darker() : bg;
        int arc = Math.max(6, r.height / 4);

        // Button background
        g.setColor(new Color(0, 0, 0, 80));
        g.fillRoundRect(r.x + 2, r.y + 2, r.width, r.height, arc, arc);
        g.setColor(fill);
        g.fillRoundRect(r.x, r.y, r.width, r.height, arc, arc);
        g.setColor(new Color(255, 255, 255, 40));
        g.drawRoundRect(r.x, r.y, r.width, r.height, arc, arc);

        // Triangle — sized as a fixed fraction of the button
        int tw = r.width  * 20 / 100;  // triangle width
        int th = r.height * 40 / 100;  // triangle height
        int cx = r.x + r.width  / 2;
        int cy = r.y + r.height / 2;

        int[] xs, ys;
        if (pointRight)
        {
            xs = new int[]{ cx - tw/2, cx - tw/2, cx + tw/2 };
            ys = new int[]{ cy - th/2, cy + th/2, cy };
        }
        else
        {
            xs = new int[]{ cx + tw/2, cx + tw/2, cx - tw/2 };
            ys = new int[]{ cy - th/2, cy + th/2, cy };
        }
        g.setColor(fg);
        g.fillPolygon(xs, ys, 3);
    }

    // -------------------------------------------------------------------------
    // Mouse click routing
    // -------------------------------------------------------------------------
    private void handleClick(int x, int y)
    {
        if (pendingPromotionMove != null)
        {
            for (int i = 0; i < 4; i++)
                if (promotionRects[i] != null && promotionRects[i].contains(x, y))
                {
                    Piece p = board.getPiece(pendingPromotionMove.endRow, pendingPromotionMove.endCol);
                    if (p != null) p.type = promotionTypes[i];
                    applyMove(pendingPromotionMove);
                    return;
                }
            return;
        }

        if (state == GameState.START)
        {
            if (btnWhite  != null && btnWhite.contains(x, y))  startGame("white");
            if (btnBlack  != null && btnBlack.contains(x, y))  startGame("black");
            if (btnRandom != null && btnRandom.contains(x, y)) startGame(new Random().nextBoolean() ? "white" : "black");
            return;
        }

        for (Map.Entry<Integer, Rectangle> entry : moveClickAreas.entrySet())
        {
            if (entry.getValue().contains(x, y))
            {
                viewIndex = entry.getKey();
                showGameOverOverlay = (viewIndex == history.size() - 1);
                mouseClicked = false;
                return;
            }
        }

        if (state == GameState.GAME_OVER)
        {
            if (btnPrev != null && btnPrev.contains(x, y))
            { if (viewIndex > 0) { viewIndex--; showGameOverOverlay = false; } }
            else if (btnNext != null && btnNext.contains(x, y))
            { if (viewIndex < history.size() - 1) { viewIndex++; showGameOverOverlay = (viewIndex == history.size() - 1); } }
            else if (btnPlayAgain != null && btnPlayAgain.contains(x, y))
            { state = GameState.START; }
            else if (x < boardSize())
            {
                // Hide overlay AND allow square selection
                showGameOverOverlay = false;
                int bs = boardSize();
                int newRow = y * 8 / bs;
                int newCol = x * 8 / bs;
                if (newRow == selectedRow && newCol == selectedCol)
                { selectedRow = -1; selectedCol = -1; }
                else
                { selectedRow = newRow; selectedCol = newCol; }
            }
        }
        else if (state == GameState.PLAYING)
        {
            if (btnPrev != null && btnPrev.contains(x, y))
            { if (viewIndex > 0) viewIndex--; mouseClicked = false; }
            else if (btnNext != null && btnNext.contains(x, y))
            { if (viewIndex < history.size() - 1) viewIndex++; mouseClicked = false; }
            else if (x < boardSize() && animMove == null)
            {
                int bs = boardSize();
                int newRow = y * 8 / bs;
                int newCol = x * 8 / bs;

                // If it's the player's turn on the live position, check for a legal move
                boolean reviewing = !history.isEmpty() && viewIndex < history.size() - 1;
                if (!reviewing && turn.equals(myColor))
                {
                    Move m = null;
                    for (Move move : selectedLocationMoves)
                        if (newRow == move.endRow && newCol == move.endCol)
                        { m = move; break; }

                    if (m != null)
                    {
                        // Queue the move for execution on the game loop thread
                        selectedRow = -1;
                        selectedCol = -1;
                        pendingPlayerMove = m;
                        mouseClicked = false;
                        return;
                    }
                }

                // Update selection (works during live play, AI turn, and history review)
                if (newRow == selectedRow && newCol == selectedCol)
                { selectedRow = -1; selectedCol = -1; }
                else
                { selectedRow = newRow; selectedCol = newCol; }
                mouseClicked = false;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Draw a piece texture directly onto a Graphics context
    // -------------------------------------------------------------------------
    @SuppressWarnings("MismatchedReadAndWriteOfArray")
    private void drawPieceGraphics(Graphics g, String texture, String color, int xStart, int yStart, int size)
    {
        int[][] tex;
        switch (texture)
        {
            case "pawn" -> tex = pawnTexture;
            case "knight" -> tex = knightTexture;
            case "bishop" -> tex = bishopTexture;
            case "rook" -> tex = rookTexture;
            case "queen" -> tex = queenTexture;
            case "king" -> tex = kingTexture;
            default -> {
                return;
            }
        }
        BufferedImage pieceImg = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        int[] pix = ((DataBufferInt) pieceImg.getRaster().getDataBuffer()).getData();
        int pieceColor = color.equals("white")
                ? (0xFF000000 | whitePieceColor)
                : (0xFF000000 | blackPieceColor);
        for (int py = 0; py < size; py++)
            for (int px = 0; px < size; px++)
            {
                int ty = py * 64 / size, tx = px * 64 / size;
                if (tex[ty][tx] == '#') pix[py * size + px] = pieceColor;
            }
        g.drawImage(pieceImg, xStart, yStart, size, size, null);
    }

    // -------------------------------------------------------------------------
    // Make a move — starts animation, then finishes via finishAnimatedMove()
    // -------------------------------------------------------------------------
    public void makeMove(Move m)
    {
        Piece movingPiece = board.getPiece(m.startRow, m.startCol);
        if (movingPiece == null) return;

        boolean isPlayerPromotion = movingPiece.type.equals("pawn")
                && m.endRow == 0
                && turn.equals(myColor);

        if (isPlayerPromotion)
        {
            // For promotion, animate first then show picker
            startAnimation(m, movingPiece.type, movingPiece.color);
            return;
        }

        startAnimation(m, movingPiece.type, movingPiece.color);
    }

    /** Kicks off the slide animation for a move. Removes the piece from its start square. */
    private void startAnimation(Move m, String pieceType, String pieceColor)
    {
        // Remove the piece from its start square so it doesn't render there during animation
        board.removePiece(m.startRow, m.startCol);
        animMove       = m;
        animPieceType  = pieceType;
        animPieceColor = pieceColor;
        animStartTime  = System.currentTimeMillis();
    }

    /** Called when the animation timer expires — actually applies the move. */
    private void finishAnimatedMove(Move m)
    {
        // Put the piece back at start so board.move() can find and move it
        Piece p = new Piece(m.startRow, m.startCol, animPieceColor, 0);
        p.type = animPieceType;
        board.pieces.add(p);

        boolean isPlayerPromotion = animPieceType.equals("pawn")
                && m.endRow == 0
                && turn.equals(myColor);

        if (isPlayerPromotion)
        {
            board.move(m);
            board.update(m);
            Piece promoted = board.getPiece(m.endRow, m.endCol);
            if (promoted != null) promoted.type = "pawn";
            pendingPromotionMove = m;
            selectedRow = -1;
            selectedCol = -1;
            return;
        }

        applyMove(m);
    }

    private void applyMove(Move m)
    {
        boolean isPromotion = (pendingPromotionMove != null);
        String label = isPromotion
                ? buildPromotionLabel(m, board.getPiece(m.endRow, m.endCol).type)
                : buildMoveLabel(m);
        String turnBefore = turn;

        if (!isPromotion)
        {
            int numPiecesBefore = board.pieces.size();
            board.move(m);
            board.update(m);
            Piece moved = board.getPiece(m.endRow, m.endCol);
            if (moved != null && (moved.type.equals("pawn") || numPiecesBefore > board.pieces.size()))
            { turnsSincePawnMoveOrCapture = 0; positionHistory.clear(); }
            else
            { turnsSincePawnMoveOrCapture++; }
        }
        else
        {
            turnsSincePawnMoveOrCapture = 0;
            positionHistory.clear();
            pendingPromotionMove = null;
        }

        turn = turn.equals("white") ? "black" : "white";
        board.flipBoard();

        Board snap = new Board(board);
        if (!snap.color.equals(myColor)) snap.flipBoard();

        history.add(new HistoryEntry(snap, label, turnBefore));
        viewIndex = history.size() - 1;
        showGameOverOverlay = true;
        moveListScrollOffset = Math.max(0, (history.size() - 1) / 2 - 3);

        if (!gameOver && recordAndCheckRepetition())
        {
            gameOverMessage = "Draw \u2014 Threefold repetition";
            gameOver = true;
            state = GameState.GAME_OVER;
        }
    }

    // -------------------------------------------------------------------------
    // Move label builders
    // -------------------------------------------------------------------------
    private String buildMoveLabel(Move m)
    {
        Piece p = board.getPiece(m.startRow, m.startCol);
        if (p == null) return "?";
        if (p.type.equals("king"))
        {
            if (m.endCol - m.startCol ==  2) return "O-O";
            if (m.endCol - m.startCol == -2) return "O-O-O";
        }
        String piece = "";
        switch (p.type)
        {
            case "knight" -> piece = "N";
            case "bishop" -> piece = "B";
            case "rook" -> piece = "R";
            case "queen" -> piece = "Q";
            case "king" -> piece = "K";
        }
        boolean capture = board.getPiece(m.endRow, m.endCol) != null;
        String from = (p.type.equals("pawn") && capture) ? String.valueOf((char)('a' + m.startCol)) : "";
        String captureStr = capture ? "x" : "";
        char file = (char)('a' + m.endCol);
        int  rank = 8 - m.endRow;
        String promo = (p.type.equals("pawn") && m.endRow == 0) ? "=Q" : "";
        return piece + from + captureStr + file + rank + promo;
    }

    private String buildPromotionLabel(Move m, String promotedType)
    {
        boolean capture = m.startCol != m.endCol;
        String from = capture ? String.valueOf((char)('a' + m.startCol)) : "";
        String captureStr = capture ? "x" : "";
        char file = (char)('a' + m.endCol);
        int  rank = 8 - m.endRow;
        String promoChar;
        promoChar = switch (promotedType) {
            case "rook" -> "=R";
            case "bishop" -> "=B";
            case "knight" -> "=N";
            default -> "=Q";
        };
        return from + captureStr + file + rank + promoChar;
    }

    // -------------------------------------------------------------------------
    // File reader for textures
    // -------------------------------------------------------------------------
    private void readFile(int[][] array, String fileLoc)
    {
        File f = new File(fileLoc);
        if (!f.exists())
        {
            String classPath = Chess.class.getProtectionDomain().getCodeSource().getLocation().getPath();
            File classDir = new File(classPath).getParentFile();
            File candidate = new File(classDir, fileLoc);
            if (!candidate.exists()) candidate = new File(classDir.getParentFile(), fileLoc);
            if (candidate.exists()) f = candidate;
        }
        try (Scanner file = new Scanner(f))
        {
            for (int y = 0; y < 64; y++)
            {
                if (!file.hasNextLine()) break;
                String line = file.nextLine();
                for (int x = 0; x < 64 && x < line.length(); x++)
                    array[y][x] = line.charAt(x);
            }
        }
        catch (IOException e)
        {
            System.err.println("Could not load texture: " + fileLoc + " (" + f.getAbsolutePath() + ")");
        }
    }

    // -------------------------------------------------------------------------
    // Mouse listeners
    // -------------------------------------------------------------------------
    @Override public void mouseClicked(MouseEvent me) {}
    @Override public void mouseEntered(MouseEvent me) {}
    @Override public void mouseExited(MouseEvent me)  {}
    @Override public void mousePressed(MouseEvent me) { mousePressed = true; }

    @Override
    public void mouseReleased(MouseEvent me)
    {
        Insets ins = getInsets();
        int cx = me.getX() - ins.left;
        int cy = me.getY() - ins.top;
        mousePressed = false;
        mouseClicked = true;
        mouseX = cx;
        mouseY = cy;
        handleClick(cx, cy);
    }

    @Override
    public void mouseDragged(MouseEvent me)
    {
        Insets ins = getInsets();
        mousePressed = true;
        mouseX = me.getX() - ins.left;
        mouseY = me.getY() - ins.top;
    }

    @Override
    public void mouseMoved(MouseEvent me)
    {
        Insets ins = getInsets();
        mousePressed = false;
        mouseX = me.getX() - ins.left;
        mouseY = me.getY() - ins.top;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    private int RGB(int r, int g, int b) { return r << 16 | g << 8 | b; }

    // -------------------------------------------------------------------------
    // Pixel-perfect square coordinate helpers.
    // Using (n+1)*bs/8 - n*bs/8 instead of bs/8 distributes rounding error
    // so squares tile exactly to boardSize with no gap at the edges.
    // -------------------------------------------------------------------------
    private static int sqX(int col, int bs) { return col * bs / 8; }
    private static int sqY(int row, int bs) { return row * bs / 8; }
    private static int sqW(int col, int bs) { return (col + 1) * bs / 8 - col * bs / 8; }
    private static int sqH(int row, int bs) { return (row + 1) * bs / 8 - row * bs / 8; }

    private String positionKey()
    {
        Board snap = new Board(board);
        if (snap.color.equals("black")) snap.flipBoard();
        StringBuilder sb = new StringBuilder(80);
        char[][] grid = new char[8][8];
        for (char[] row : grid) Arrays.fill(row, '.');
        for (Piece p : snap.pieces)
        {
            char t = p.type.equals("knight") ? 'n' : p.type.charAt(0);
            grid[p.row][p.col] = p.color.equals("white") ? Character.toUpperCase(t) : t;
        }
        for (char[] row : grid) sb.append(new String(row));
        sb.append(turn.charAt(0));
        sb.append(snap.whiteCastle[0] ? 'Q' : '-');
        sb.append(snap.whiteCastle[1] ? 'K' : '-');
        sb.append(snap.blackCastle[0] ? 'q' : '-');
        sb.append(snap.blackCastle[1] ? 'k' : '-');
        if (snap.previousMove != null && snap.pieceMoved != null
                && snap.pieceMoved.type.equals("pawn")
                && Math.abs(snap.previousMove.startRow - snap.previousMove.endRow) == 2)
            sb.append((char)('a' + snap.previousMove.endCol));
        else
            sb.append('-');
        return sb.toString();
    }

    private boolean recordAndCheckRepetition()
    {
        String key = positionKey();
        int count = positionHistory.getOrDefault(key, 0) + 1;
        positionHistory.put(key, count);
        return count >= 3;
    }

    public static void main(String[] args) { new Chess(); }
}
