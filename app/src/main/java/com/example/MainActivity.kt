package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.*

// Game Screen Navigation
enum class Screen {
    WELCOME,
    GAME
}

// Letter Status Evaluation
enum class CharEvaluation {
    CORRECT,      // 👑 - Correct character and position
    WRONG_PLACE,  // 🟡 - Correct character, wrong position
    NOT_PRESENT   // ⚪ - Not in the word
}

// Model for Guess Row
data class GuessRow(
    val word: String,
    val isSubmitted: Boolean = false,
    val evaluation: List<CharEvaluation> = emptyList()
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme(darkTheme = true, dynamicColor = false) {
                // Ensure RTL layout direction for Arabic
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Scaffold(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(SlateDark)
                    ) { innerPadding ->
                        AlWerdApp(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        )
                    }
                }
            }
        }
    }
}

// Wordle evaluation logic
fun evaluateGuess(guess: String, secret: String): List<CharEvaluation> {
    val size = secret.length
    val result = MutableList(size) { CharEvaluation.NOT_PRESENT }
    
    // Normalize characters for comparison (unify hamzat and Taa Marbouta)
    val secretNormalized = WordDatabase.normalizeString(secret)
    val guessNormalized = WordDatabase.normalizeString(guess)
    
    val secretUsed = BooleanArray(size) { false }
    val guessUsed = BooleanArray(size) { false }
    
    // First pass: exact matches
    for (i in 0 until size) {
        if (guessNormalized[i] == secretNormalized[i]) {
            result[i] = CharEvaluation.CORRECT
            secretUsed[i] = true
            guessUsed[i] = true
        }
    }
    
    // Second pass: partial matches (wrong position)
    for (i in 0 until size) {
        if (!guessUsed[i]) {
            for (j in 0 until size) {
                if (!secretUsed[j] && guessNormalized[i] == secretNormalized[j]) {
                    result[i] = CharEvaluation.WRONG_PLACE
                    secretUsed[j] = true
                    break
                }
            }
        }
    }
    
    return result
}

// Game View Model
class AlWerdViewModel : ViewModel() {
    var currentScreen by mutableStateOf(Screen.WELCOME)
    var wordLength by mutableIntStateOf(4)
    var secretWord by mutableStateOf("")
    
    val guesses = mutableStateListOf<GuessRow>()
    var currentGuessIndex by mutableIntStateOf(0)
    var currentWordInput by mutableStateOf("")
    
    // Key-value status of each letter on the keyboard
    val letterStatuses = mutableStateMapOf<Char, CharEvaluation>()
    
    var gameWon by mutableStateOf(false)
    var gameFinished by mutableStateOf(false)
    var totalAttemptsUsed by mutableIntStateOf(0)
    var showOutcomeDialog by mutableStateOf(false)
    var winStreak by mutableIntStateOf(0)

    // SharedPreferences Statistics
    var gamesPlayed by mutableIntStateOf(0)
    var gamesWon by mutableIntStateOf(0)
    var maxStreak by mutableIntStateOf(0)
    val guessDistribution = mutableStateListOf(0, 0, 0, 0, 0, 0)
    private var isStatsLoaded = false

    // Royal Hint states
    var hintUsedThisRound by mutableStateOf(false)
    var activeHintText by mutableStateOf<String?>(null)

    fun ensureStatsLoaded(context: android.content.Context) {
        if (isStatsLoaded) return
        val prefs = context.getSharedPreferences("alwerd_stats", android.content.Context.MODE_PRIVATE)
        gamesPlayed = prefs.getInt("games_played", 0)
        gamesWon = prefs.getInt("games_won", 0)
        winStreak = prefs.getInt("win_streak", 0)
        maxStreak = prefs.getInt("max_streak", 0)
        for (i in 0 until 6) {
            guessDistribution[i] = prefs.getInt("guess_dist_$i", 0)
        }
        isStatsLoaded = true
    }

    fun recordGameOutcome(context: android.content.Context, won: Boolean, attempts: Int) {
        ensureStatsLoaded(context)
        gamesPlayed++
        if (won) {
            gamesWon++
            winStreak++
            if (winStreak > maxStreak) {
                maxStreak = winStreak
            }
            if (attempts in 1..6) {
                guessDistribution[attempts - 1]++
            }
        } else {
            winStreak = 0
        }
        
        val prefs = context.getSharedPreferences("alwerd_stats", android.content.Context.MODE_PRIVATE)
        prefs.edit().apply {
            putInt("games_played", gamesPlayed)
            putInt("games_won", gamesWon)
            putInt("win_streak", winStreak)
            putInt("max_streak", maxStreak)
            for (i in 0 until 6) {
                putInt("guess_dist_$i", guessDistribution[i])
            }
            apply()
        }
    }
    
    fun startGame(length: Int) {
        wordLength = length
        resetGame()
        val wordList = WordDatabase.getWordsForLength(length)
        secretWord = if (wordList.isNotEmpty()) {
            wordList.random()
        } else {
            // Safe fallback
            when (length) {
                3 -> "قلم"
                4 -> "كتاب"
                5 -> "مفتاح"
                6 -> "كمبيوتر"
                else -> "كتاب"
            }
        }
        currentScreen = Screen.GAME
    }
    
    fun resetGame() {
        guesses.clear()
        repeat(6) {
            guesses.add(GuessRow(word = ""))
        }
        currentGuessIndex = 0
        currentWordInput = ""
        letterStatuses.clear()
        gameWon = false
        gameFinished = false
        totalAttemptsUsed = 0
        showOutcomeDialog = false
        hintUsedThisRound = false
        activeHintText = null
    }
    
    fun onKeyPress(char: Char) {
        if (gameFinished) return
        if (currentWordInput.length < wordLength) {
            AudioSynth.playTap()
            currentWordInput += char
            guesses[currentGuessIndex] = GuessRow(word = currentWordInput)
        }
    }
    
    fun onDelete() {
        if (gameFinished) return
        if (currentWordInput.isNotEmpty()) {
            AudioSynth.playTap()
            currentWordInput = currentWordInput.dropLast(1)
            guesses[currentGuessIndex] = GuessRow(word = currentWordInput)
        }
    }
    
    fun onConfirm(context: android.content.Context) {
        if (gameFinished) return
        if (currentWordInput.length < wordLength) {
            Toast.makeText(context, "الرجاء إكمال حروف الكلمة أولاً! ⚠️", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Evaluate Guess
        val evaluation = evaluateGuess(currentWordInput, secretWord)
        
        // Mark row as submitted
        guesses[currentGuessIndex] = GuessRow(
            word = currentWordInput,
            isSubmitted = true,
            evaluation = evaluation
        )
        
        // Update Keyboard statuses based on normalized characters
        val normalizedGuess = WordDatabase.normalizeString(currentWordInput)
        for (i in normalizedGuess.indices) {
            val char = normalizedGuess[i]
            val eval = evaluation[i]
            val existing = letterStatuses[char]
            
            if (existing == null || 
                existing == CharEvaluation.NOT_PRESENT || 
                (existing == CharEvaluation.WRONG_PLACE && eval == CharEvaluation.CORRECT)) {
                letterStatuses[char] = eval
            }
        }
        
        // Check Win
        val isWin = evaluation.all { it == CharEvaluation.CORRECT }
        if (isWin) {
            gameWon = true
            gameFinished = true
            totalAttemptsUsed = currentGuessIndex + 1
            recordGameOutcome(context, won = true, attempts = totalAttemptsUsed)
            AudioSynth.playWin()
            showOutcomeDialog = true
            return
        }
        
        // Move to next attempt
        if (currentGuessIndex < 5) {
            AudioSynth.playTap()
            currentGuessIndex++
            currentWordInput = ""
        } else {
            // Loss
            gameWon = false
            gameFinished = true
            totalAttemptsUsed = 6
            recordGameOutcome(context, won = false, attempts = 6)
            AudioSynth.playLose()
            showOutcomeDialog = true
        }
    }

    fun requestRoyalHint(context: android.content.Context) {
        if (gameFinished) return
        if (hintUsedThisRound) {
            Toast.makeText(context, "لقد استخدمت تلميحك الملكي بالفعل في هذه الجولة! 🔮", Toast.LENGTH_SHORT).show()
            return
        }

        val secretNormalized = WordDatabase.normalizeString(secretWord)
        
        // Identify which indices in the secret word are already correctly solved by the user
        val correctIndices = mutableSetOf<Int>()
        for (row in guesses) {
            if (row.isSubmitted) {
                val rowNormalized = WordDatabase.normalizeString(row.word)
                for (i in rowNormalized.indices) {
                    if (i < secretNormalized.length && rowNormalized[i] == secretNormalized[i]) {
                        correctIndices.add(i)
                    }
                }
            }
        }

        // Filter indices that haven't been correctly guessed yet
        val unsolvedIndices = (0 until wordLength).filter { it !in correctIndices }
        val indexToReveal = if (unsolvedIndices.isNotEmpty()) {
            unsolvedIndices.random()
        } else {
            (0 until wordLength).random()
        }

        val revealedChar = secretWord[indexToReveal]
        activeHintText = "الحرف المُرشد هو « $revealedChar » ويقع في الخانة رقم ${indexToReveal + 1}"
        hintUsedThisRound = true
        AudioSynth.playHint()
    }
    
    fun getShareableResult(): String {
        val sb = StringBuilder()
        sb.append("📝 لعبة الوِرد\n")
        sb.append("🎯 التحدي: كلمة من $wordLength أحرف\n")
        val attemptsText = if (gameWon) "$totalAttemptsUsed/٦" else "خسارة"
        sb.append("🏆 المحاولات: $attemptsText\n\n")
        
        for (i in 0..currentGuessIndex) {
            val row = guesses[i]
            if (row.isSubmitted) {
                val emojiRow = row.evaluation.joinToString("   ") { eval ->
                    when (eval) {
                        CharEvaluation.CORRECT -> "👑"
                        CharEvaluation.WRONG_PLACE -> "🟡"
                        CharEvaluation.NOT_PRESENT -> "⚪"
                    }
                }
                sb.append(emojiRow).append("\n")
                // Spaced Arabic characters
                val charsRow = row.word.map { it }.joinToString("   ")
                sb.append(charsRow).append("\n\n")
            }
        }
        sb.append("للعب حمّل تطبيق الوِرد الفاخر ✨")
        return sb.toString()
    }
}

@Composable
fun AlWerdApp(
    modifier: Modifier = Modifier,
    viewModel: AlWerdViewModel = viewModel()
) {
    Surface(
        modifier = modifier,
        color = SlateDark
    ) {
        AnimatedContent(
            targetState = viewModel.currentScreen,
            transitionSpec = {
                fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
            },
            label = "ScreenTransition"
        ) { screen ->
            when (screen) {
                Screen.WELCOME -> WelcomeScreen(
                    viewModel = viewModel,
                    onDifficultySelected = { length ->
                        viewModel.startGame(length)
                    }
                )
                Screen.GAME -> GameScreen(
                    viewModel = viewModel,
                    onBackToWelcome = {
                        viewModel.currentScreen = Screen.WELCOME
                    }
                )
            }
        }
    }
}

// 1. WELCOME & DIFFICULTY SELECTION
@Composable
fun WelcomeScreen(
    viewModel: AlWerdViewModel,
    onDifficultySelected: (Int) -> Unit
) {
    val context = LocalContext.current
    var showRulesDialog by remember { mutableStateOf(false) }
    var showStatsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.ensureStatsLoaded(context)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(EmeraldDark, SlateDark)
                )
            )
            .padding(24.dp)
    ) {
        // Aesthetic Top Corner Lines/Frame
        Canvas(modifier = Modifier.fillMaxSize()) {
            val margin = 20.dp.toPx()
            val cornerLen = 40.dp.toPx()
            
            // Top Right Corner
            drawLine(
                color = GoldLuxury,
                start = Offset(cornerLen, margin),
                end = Offset(margin, margin),
                strokeWidth = 3f
            )
            drawLine(
                color = GoldLuxury,
                start = Offset(margin, cornerLen),
                end = Offset(margin, margin),
                strokeWidth = 3f
            )
            
            // Bottom Left Corner
            drawLine(
                color = GoldLuxury,
                start = Offset(size.width - cornerLen, size.height - margin),
                end = Offset(size.width - margin, size.height - margin),
                strokeWidth = 3f
            )
            drawLine(
                color = GoldLuxury,
                start = Offset(size.width - margin, size.height - cornerLen),
                end = Offset(size.width - margin, size.height - margin),
                strokeWidth = 3f
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 24.dp)
            ) {
                Text(
                    text = "لعبة الوِرد 📝",
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                    color = GoldLuxury,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.displayMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "تخمين الكلمات العربية الراقية",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Light,
                    color = PearlGrey,
                    textAlign = TextAlign.Center
                )
                
                if (viewModel.winStreak > 0) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(EmeraldMedium, RoundedCornerShape(20.dp))
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .border(1.dp, GoldLuxury, RoundedCornerShape(20.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Streak",
                            tint = GoldLuxury,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "سلسلة الفوز المتتالي: ${viewModel.winStreak} 🔥",
                            color = PearlWhite,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Elegant actions: Rules & Statistics
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { showRulesDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldMedium),
                    border = BorderStroke(1.5.dp, GoldLuxury),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) {
                    Text("شرح قواعد اللعبة 📖", color = GoldLuxury, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }

                Button(
                    onClick = { showStatsDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldMedium),
                    border = BorderStroke(1.5.dp, GoldLuxury),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) {
                    Text("الإحصائيات الملكية 📊", color = GoldLuxury, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }

            // Difficulty select
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "اختر طول الكلمة لبدء التحدي:",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = GoldLight,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    listOf(3, 4, 5, 6).forEach { size ->
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(EmeraldMedium)
                                .border(BorderStroke(1.5.dp, GoldLuxury), RoundedCornerShape(12.dp))
                                .clickable { onDifficultySelected(size) }
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "$size",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = GoldLuxury
                                )
                                Text(
                                    text = "أحرف",
                                    fontSize = 10.sp,
                                    color = PearlGrey
                                )
                            }
                        }
                    }
                }
            }
        }

        // Dialogs
        if (showRulesDialog) {
            RulesDialog(onDismiss = { showRulesDialog = false })
        }

        if (showStatsDialog) {
            StatisticsDialog(viewModel = viewModel, onDismiss = { showStatsDialog = false })
        }
    }
}

// 2. GAME SCREEN
@Composable
fun GameScreen(
    viewModel: AlWerdViewModel,
    onBackToWelcome: () -> Unit
) {
    val context = LocalContext.current
    var showStatsDialog by remember { mutableStateOf(false) }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SlateDark)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(EmeraldDark)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .border(
                        BorderStroke(0.5.dp, GoldLuxury.copy(alpha = 0.3f)),
                        RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onBackToWelcome) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "الرجوع",
                        tint = GoldLuxury
                    )
                }
                
                Text(
                    text = "الوِرد 📝 (${viewModel.wordLength} أحرف)",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = GoldLuxury
                )
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showStatsDialog = true }) {
                        Text("📊", fontSize = 22.sp)
                    }
                    IconButton(onClick = { viewModel.resetGame(); viewModel.startGame(viewModel.wordLength) }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "تحديث اللعبة",
                            tint = GoldLuxury
                        )
                    }
                }
            }

            // Hint Text Banner (Visible when hint is actively requested)
            AnimatedVisibility(
                visible = viewModel.activeHintText != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 12.dp)
            ) {
                viewModel.activeHintText?.let { hint ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = EmeraldDark),
                        border = BorderStroke(1.5.dp, GoldLuxury),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "🔮",
                                fontSize = 24.sp,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                            Text(
                                text = hint,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = GoldLuxury,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // Word Guess Grid Box
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.wrapContentSize()
                ) {
                    items(6) { index ->
                        val row = viewModel.guesses.getOrNull(index) ?: GuessRow(word = "")
                        val isActive = index == viewModel.currentGuessIndex && !viewModel.gameFinished
                        
                        GuessRowView(
                            row = row,
                            length = viewModel.wordLength,
                            isActive = isActive,
                            currentInput = if (isActive) viewModel.currentWordInput else ""
                        )
                    }
                }
            }

            // Royal Hint Trigger Banner (only visible if the round is active)
            if (!viewModel.gameFinished) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Button(
                        onClick = { viewModel.requestRoyalHint(context) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (viewModel.hintUsedThisRound) EmeraldMedium else GoldLuxury,
                            disabledContainerColor = EmeraldMedium
                        ),
                        border = BorderStroke(1.dp, GoldLuxury),
                        shape = RoundedCornerShape(20.dp),
                        enabled = !viewModel.hintUsedThisRound,
                        modifier = Modifier.height(38.dp)
                    ) {
                        Text(
                            text = if (viewModel.hintUsedThisRound) "تم استخدام تلميحك الملكي 🔮" else "طلب تلميح ملكي 🔮 (مرة واحدة)",
                            color = if (viewModel.hintUsedThisRound) PearlGrey else SlateDark,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Virtual Elegant Arabic Keyboard
            KeyboardArea(
                onKeyPress = { viewModel.onKeyPress(it) },
                onDelete = { viewModel.onDelete() },
                onConfirm = { viewModel.onConfirm(context) },
                letterStatuses = viewModel.letterStatuses
            )
        }

        // Dialogs & Overlays
        if (viewModel.showOutcomeDialog) {
            OutcomeDialog(viewModel = viewModel)
        }

        if (showStatsDialog) {
            StatisticsDialog(viewModel = viewModel, onDismiss = { showStatsDialog = false })
        }
    }
}

// SINGLE WORDLE GRID ROW WITH LETTERS AND LUXURIOUS SPACING
@Composable
fun GuessRowView(
    row: GuessRow,
    length: Int,
    isActive: Boolean,
    currentInput: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Emojis status indicator row (Only shown if submitted, exactly as requested)
        if (row.isSubmitted) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                row.evaluation.forEach { eval ->
                    val emoji = when (eval) {
                        CharEvaluation.CORRECT -> "👑"
                        CharEvaluation.WRONG_PLACE -> "🟡"
                        CharEvaluation.NOT_PRESENT -> "⚪"
                    }
                    Text(
                        text = emoji,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(42.dp)
                    )
                }
            }
        }

        // Letter Squares Row
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 0 until length) {
                val char = when {
                    row.isSubmitted -> row.word.getOrNull(i)
                    isActive -> currentInput.getOrNull(i)
                    else -> row.word.getOrNull(i)
                }
                
                val eval = row.evaluation.getOrNull(i)
                
                val containerColor = when {
                    row.isSubmitted && eval == CharEvaluation.CORRECT -> EmeraldBright
                    row.isSubmitted && eval == CharEvaluation.WRONG_PLACE -> GoldLuxury
                    row.isSubmitted -> SlateDark
                    isActive && i == currentInput.length -> EmeraldMedium
                    else -> SlateDark
                }
                
                val borderStroke = when {
                    row.isSubmitted -> BorderStroke(1.5.dp, GoldLuxury.copy(alpha = 0.8f))
                    isActive && i == currentInput.length -> BorderStroke(2.dp, GoldLight)
                    else -> BorderStroke(1.dp, PearlGrey.copy(alpha = 0.5f))
                }

                val textColor = when {
                    row.isSubmitted && eval == CharEvaluation.WRONG_PLACE -> SlateDark
                    else -> PearlWhite
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(containerColor)
                        .border(borderStroke, RoundedCornerShape(8.dp))
                ) {
                    Text(
                        text = char?.toString() ?: "",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// VIRTUAL CUSTOM ARABIC KEYBOARD WITH REAL-TIME STATUS COLORING
@Composable
fun KeyboardArea(
    onKeyPress: (Char) -> Unit,
    onDelete: () -> Unit,
    onConfirm: () -> Unit,
    letterStatuses: Map<Char, CharEvaluation>
) {
    // 3 Symmetrical rows of Arabic characters
    val row1 = listOf('ض', 'ص', 'ث', 'ق', 'ف', 'غ', 'ع', 'ه', 'خ', 'ح', 'ج')
    val row2 = listOf('ش', 'س', 'ي', 'ب', 'ل', 'ا', 'ت', 'ن', 'م', 'ك', 'ط')
    val row3 = listOf('ذ', 'ء', 'ؤ', 'ئ', 'ر', 'ى', 'ة', 'و', 'ز', 'ظ', 'د')

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(EmeraldDark)
            .border(
                BorderStroke(0.5.dp, GoldLuxury.copy(alpha = 0.3f)),
                RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Row 1
        KeyboardRow(letters = row1, onKeyPress = onKeyPress, letterStatuses = letterStatuses)
        // Row 2
        KeyboardRow(letters = row2, onKeyPress = onKeyPress, letterStatuses = letterStatuses)
        // Row 3
        KeyboardRow(letters = row3, onKeyPress = onKeyPress, letterStatuses = letterStatuses)
        
        // Actions bottom row (Enter / Backspace)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Confirm/Enter
            Button(
                onClick = onConfirm,
                modifier = Modifier
                    .weight(1.5f)
                    .height(44.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GoldLuxury),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "تأكـيـد 👑",
                    color = SlateDark,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
            
            // Delete/Backspace
            Button(
                onClick = onDelete,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BurgundyLuxury),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "حـذف ⌫",
                    color = PearlWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
fun KeyboardRow(
    letters: List<Char>,
    onKeyPress: (Char) -> Unit,
    letterStatuses: Map<Char, CharEvaluation>
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
    ) {
        letters.forEach { char ->
            val normalizedChar = WordDatabase.normalizeChar(char)
            val status = letterStatuses[normalizedChar] ?: letterStatuses[char]
            
            val buttonColor = when (status) {
                CharEvaluation.CORRECT -> EmeraldBright
                CharEvaluation.WRONG_PLACE -> GoldLuxury
                CharEvaluation.NOT_PRESENT -> Color(0xFF1E2824)
                else -> EmeraldMedium
            }

            val textColor = when (status) {
                CharEvaluation.WRONG_PLACE -> SlateDark
                CharEvaluation.NOT_PRESENT -> PearlWhite.copy(alpha = 0.4f)
                else -> PearlWhite
            }
            
            val borderStroke = when (status) {
                null -> BorderStroke(0.5.dp, GoldLuxury.copy(alpha = 0.3f))
                else -> null
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(buttonColor)
                    .then(if (borderStroke != null) Modifier.border(borderStroke, RoundedCornerShape(6.dp)) else Modifier)
                    .clickable { onKeyPress(char) }
            ) {
                Text(
                    text = char.toString(),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// LUXURIOUS GAME OVER POPUP DIALOG (WIN & LOSS)
@Composable
fun OutcomeDialog(
    viewModel: AlWerdViewModel
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = { viewModel.showOutcomeDialog = false },
        confirmButton = {
            Button(
                onClick = {
                    val shareText = viewModel.getShareableResult()
                    clipboardManager.setText(AnnotatedString(shareText))
                    Toast.makeText(context, "تم نسخ النتيجة الفاخرة للجريدة! 📋", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = GoldLuxury),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Share, contentDescription = "مشاركة", tint = SlateDark)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "مشاركة النتيجة", color = SlateDark, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    viewModel.showOutcomeDialog = false
                    viewModel.resetGame()
                    viewModel.startGame(viewModel.wordLength)
                }
            ) {
                Text(text = "تحدي جديد ✨", color = GoldLight, fontWeight = FontWeight.Bold)
            }
        },
        title = {
            Text(
                text = if (viewModel.gameWon) "فوز مـلـكـي! 🎉👑" else "انتهت المحاولات ⚜️",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = GoldLuxury,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (viewModel.gameWon) {
                        "لقد أثبتّ فطنتك وذكاءك الملكي بمطابقة جميع الأحرف في ${viewModel.totalAttemptsUsed} محاولات! 🌟"
                    } else {
                        "حظاً أوفر في المرة القادمة. الكلمة السرية المطلوبة كانت:"
                    },
                    fontSize = 15.sp,
                    color = PearlWhite,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Reveal Word Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = EmeraldMedium),
                    border = BorderStroke(1.5.dp, GoldLuxury),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = viewModel.secretWord,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = GoldLuxury,
                            letterSpacing = 2.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "كلمة من ${viewModel.wordLength} أحرف",
                            fontSize = 12.sp,
                            color = PearlGrey
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Show streak
                Text(
                    text = "سلسلة الفوز المتتالي الحالية: ${viewModel.winStreak} 🔥",
                    fontSize = 14.sp,
                    color = GoldLight,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        containerColor = EmeraldDark,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.border(BorderStroke(1.dp, GoldLuxury), RoundedCornerShape(16.dp))
    )
}

// 3. EXPLANATION DIALOG
@Composable
fun RulesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = GoldLuxury),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("مفهوم وبدء اللعب 👑", color = SlateDark, fontWeight = FontWeight.Bold)
            }
        },
        title = {
            Text(
                text = "قواعد اللعبة الفاخرة 👑",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = GoldLuxury,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "• خمن الكلمة المجهولة في ٦ محاولات فقط.\n\n" +
                           "• 👑 يعني الحرف صحيح في مكانه الصحيح (الأخضر الملكي).\n\n" +
                           "• 🟡 يعني الحرف موجود بالكلمة لكن بمكان آخر (الذهبي الملكي).\n\n" +
                           "• ⚪ يعني الحرف غير موجود بالكلمة إطلاقاً (اللؤلؤي الملكي).\n\n" +
                           "• يتم دمج الهمزات وتوحيد التاء المربوطة لتسهيل لعبك.\n\n" +
                           "• لديك تلميح ملكي واحد (🔮) في كل جولة، يكشف لك حرفاً في مكانه الصحيح ليرشدك للحل الملكي!",
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    color = PearlWhite,
                    textAlign = TextAlign.Right
                )
            }
        },
        containerColor = EmeraldDark,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.border(BorderStroke(1.5.dp, GoldLuxury), RoundedCornerShape(16.dp))
    )
}

// 4. STATISTICS DIALOG
@Composable
fun StatisticsDialog(viewModel: AlWerdViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.ensureStatsLoaded(context)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = GoldLuxury),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("إغلاق ⚜️", color = SlateDark, fontWeight = FontWeight.Bold)
            }
        },
        title = {
            Text(
                text = "الإحصائيات الملكية 📊",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = GoldLuxury,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Numbers row
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${viewModel.gamesPlayed}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = GoldLuxury)
                        Text("الملعوبة", fontSize = 12.sp, color = PearlGrey)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val winPercent = if (viewModel.gamesPlayed > 0) (viewModel.gamesWon * 100 / viewModel.gamesPlayed) else 0
                        Text("$winPercent%", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = GoldLuxury)
                        Text("نسبة الفوز", fontSize = 12.sp, color = PearlGrey)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${viewModel.winStreak}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = GoldLuxury)
                        Text("السلسلة الحالية", fontSize = 12.sp, color = PearlGrey)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${viewModel.maxStreak}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = GoldLuxury)
                        Text("أفضل سلسلة", fontSize = 12.sp, color = PearlGrey)
                    }
                }

                Divider(color = GoldLuxury.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = "توزيع التخمينات الناجحة:",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = GoldLight,
                    modifier = Modifier.align(Alignment.Start).padding(bottom = 8.dp)
                )

                // Simple bar chart distribution
                val maxCount = viewModel.guessDistribution.maxOrNull() ?: 1
                val safeMaxCount = if (maxCount == 0) 1 else maxCount

                for (i in 0 until 6) {
                    val count = viewModel.guessDistribution[i]
                    val fraction = count.toFloat() / safeMaxCount
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = "${i + 1}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = PearlWhite,
                            modifier = Modifier.width(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(18.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF162B21))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(if (fraction > 0f) fraction else 0.05f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (count > 0) EmeraldBright else PearlGrey.copy(alpha = 0.3f))
                                    .padding(horizontal = 4.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Text(
                                    text = "$count",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PearlWhite
                                )
                            }
                        }
                    }
                }
            }
        },
        containerColor = EmeraldDark,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.border(BorderStroke(1.5.dp, GoldLuxury), RoundedCornerShape(16.dp))
    )
}
