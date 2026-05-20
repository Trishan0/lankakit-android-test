package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val model: LankaKitViewModel = viewModel()
      MyApplicationTheme(darkTheme = model.isDarkTheme.value, dynamicColor = false) {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = MaterialTheme.colorScheme.background
        ) {
          LankaKitApp(model)
        }
      }
    }
  }
}

// --- Live Offline AI Setup & OkHttp client for Gemini REST API (Option B) ---
object GeminiNetwork {
  private val okHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

  suspend fun askGemini(prompt: String): String = withContext(Dispatchers.IO) {
    val key = BuildConfig.GEMINI_API_KEY
    if (key.isEmpty() || key == "MY_GEMINI_API_KEY") {
      return@withContext "API Key is missing! Configure GEMINI_API_KEY in AI Studio Secrets tab.\n(ලංකා AI සක්‍රීය කිරීමට කරුණාකර AI Studio UI එකෙහි Secrets tab එකට GEMINI_API_KEY එක ඇතුලත් කරන්න.)"
    }

    val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
    // Prepare escaped JSON
    val escapedPrompt = prompt
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")

    val requestBodyJson = """
      {
        "contents": [
          {
            "parts": [
              {
                "text": "$escapedPrompt"
              }
            ]
          }
        ],
        "generationConfig": {
          "temperature": 0.7,
          "topP": 0.95
        }
      }
    """.trimIndent()

    val body = RequestBody.create(mediaType, requestBodyJson)
    val request = Request.Builder()
      .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$key")
      .post(body)
      .build()

    try {
      okHttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
          return@withContext "Error query: Server returned code ${response.code}\nResponse: ${response.message}"
        }
        val rawJson = response.body?.string() ?: return@withContext "Response body is empty."
        
        // Quick regex parser to safely extract the first part of generated content without complex reflections
        val textRegex = """"text"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""".toRegex()
        val matchResult = textRegex.find(rawJson)
        if (matchResult != null) {
          val value = matchResult.groupValues[1]
          value
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
        } else {
          if (rawJson.contains("error")) {
            "API Error returned: $rawJson"
          } else {
            "Generated text block. (පිළිතුර සකසා ගැනීමේ දෝෂයකි.)\nFull response:\n$rawJson"
          }
        }
      }
    } catch (e: Exception) {
      "Failed to connect to AI server: ${e.localizedMessage}\nCheck internet connection. (ජාල සබඳතාවය පරීක්ෂා කරන්න.)"
    }
  }
}

// --- MASTER STATE MANAGER ---
class LankaKitViewModel : ViewModel() {
  // Navigation: null = Home Dashboard, 0..9 = Respective Tool pages
  var activeTool = mutableStateOf<Int?>(null)
  
  // Custom theme toggler
  var isDarkTheme = mutableStateOf(true)

  // Language: "SI" (Sinhala-English Bilingual), "EN" (English Only)
  var appLanguage = mutableStateOf("SI")

  // Dashboard search query
  var searchQuery = mutableStateOf("")

  // --- MODEL DATA HOLDERS ---

  // Tool 1: LKR Gold & Currency Converter State
  var crAmountInput = mutableStateOf("100")
  var crSelectedCurrency = mutableStateOf("USD")
  var goldSovereigns = mutableStateOf("1")
  var goldCaratWeight = mutableStateOf("22K")

  // Tool 2: Bill Splitter
  var bsSubtotal = mutableStateOf("1500")
  var bsIncludeServiceCharge = mutableStateOf(true) // 10%
  var bsIncludeVat = mutableStateOf(true)           // 15%
  var bsIncludeSscl = mutableStateOf(true)         // 2.5%
  var bsPeopleCount = mutableStateOf("4")

  // Tool 3: Fuel Estimator
  var feSelectedVehicle = mutableStateOf("Sedan Car (කාර්)")
  var feDistance = mutableStateOf("45")
  var feCustomMileage = mutableStateOf("")
  var feCustomFuelPrice = mutableStateOf("")

  // Tool 4: Traditional Subha Welawa & Auspicious Times
  var selectedDayOfWeek = mutableStateOf(0) // 0=Sunday, 1=Monday...

  // Tool 5: LankaAI Chat Help
  var aiInputText = mutableStateOf("")
  var aiConversationResponse = mutableStateOf("ආයුබෝවන්! LankaAI වෙත සාදරයෙන් පිළිගනිමු.\nAsk me anything or tap standard templates below! (පහත සූදානම් ප්‍රශ්නයක් තෝරා හෝ විමසන්න.)")
  var isAiRequestRunning = mutableStateOf(false)

  // Tool 6: Sinhala Meme Typography Canvas
  var memeTopText = mutableStateOf("අම්මෝ ඒක!")
  var memeBottomText = mutableStateOf("ආතල් එක කෝටිපතියි!")
  var selectedGradientIndex = mutableStateOf(0)
  var memeTextScale = mutableStateOf(24f)

  // Tool 8: Bus Ticket Price Estimator
  var transitDistance = mutableStateOf("25")
  var transitSelectedClass = mutableStateOf("Normal (සාමාන්‍ය බස්)")

  // Tool 9: DLB/NLB style Lottery Lucky Simulator
  var lottoTargetLetter = mutableStateOf("අ")
  var lottoTargetNum1 = mutableStateOf("07")
  var lottoTargetNum2 = mutableStateOf("18")
  var lottoTargetNum3 = mutableStateOf("25")
  var lottoTargetNum4 = mutableStateOf("42")
  var isLottoSpinning = mutableStateOf(false)
  var lottoResultLetter = mutableStateOf("?")
  var lottoResultNums = mutableStateListOf<String>("?", "?", "?", "?")
  var lottoResultMessage = mutableStateOf("ඔබගේ ටිකට්පත් අංක තබා 'Draw Lottery' ඔබන්න.")

  // Tool 10: Sinhala Wadan WhatsApp Quote Box
  var wadanSelectedCategory = mutableStateOf("මෝටිවේෂන් (Motivational)")
  var currentWadanValue = mutableStateOf("අනෙක් අය අසාර්ථකයි කියන තැනින් ඔබේ ජයග්‍රහණය පටන් ගන්න!")
}

// --- ROOT COMPOSABLE APP SYSTEM ---
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LankaKitApp(model: LankaKitViewModel) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()

  // Responsive Layout detection helper
  BoxWithConstraints {
    val isTablet = maxWidth > 650.dp
    
    // Gradient definitions
    val themeBackgroundBrush = if (model.isDarkTheme.value) {
      Brush.verticalGradient(colors = listOf(ObsidianBlack, Color(0xFF16161F)))
    } else {
      Brush.verticalGradient(colors = listOf(LightBackground, Color(0xFFEBEFF5)))
    }

    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(themeBackgroundBrush)
        .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
      Column(modifier = Modifier.fillMaxSize()) {
        // App Header Toolbar
        Card(
          modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
          colors = CardDefaults.cardColors(
            containerColor = if (model.isDarkTheme.value) DarkSurface else LightSurface
          ),
          elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
          shape = RoundedCornerShape(16.dp)
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              // National Styled App Icon
              Box(
                modifier = Modifier
                  .size(42.dp)
                  .clip(CircleShape)
                  .background(LankaMaroon),
                contentAlignment = Alignment.Center
              ) {
                Text(
                  text = "ලං",
                  color = LankaGold,
                  fontSize = 20.sp,
                  fontWeight = FontWeight.Bold
                )
              }
              Spacer(modifier = Modifier.width(10.dp))
              Column {
                Text(
                  text = if (model.appLanguage.value == "SI") "LankaKit (ලංකා කිට්)" else "LankaKit",
                  fontSize = 18.sp,
                  fontWeight = FontWeight.ExtraBold,
                  color = if (model.isDarkTheme.value) LankaGold else LankaMaroon
                )
                Text(
                  text = if (model.appLanguage.value == "SI") "දිවයිනේ අංක 1 සුපිරි මෙවලම් පෙළ" else "No.1 Sri Lankan Utility Suite",
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Light,
                  color = if (model.isDarkTheme.value) LightGrayText else Color.DarkGray
                )
              }
            }

            // Theme & Language Settings Toggles
            Row(verticalAlignment = Alignment.CenterVertically) {
              IconButton(onClick = { model.isDarkTheme.value = !model.isDarkTheme.value }) {
                Icon(
                  imageVector = if (model.isDarkTheme.value) Icons.Default.LightMode else Icons.Default.DarkMode,
                  contentDescription = "Theme Toggle",
                  tint = if (model.isDarkTheme.value) LankaGold else LankaMaroon
                )
              }
              
              TextButton(
                onClick = { model.appLanguage.value = if (model.appLanguage.value == "SI") "EN" else "SI" }
              ) {
                Text(
                  text = if (model.appLanguage.value == "SI") "සිංහල / EN" else "English Only",
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Bold,
                  color = if (model.isDarkTheme.value) LankaGoldDark else LankaTeal
                )
              }
            }
          }
        }

        // Split Main View: Responsive Grid or Nested Tool View
        if (isTablet) {
          // TABLET Split Side-by-Side Pane
          Row(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            // Left Selection Grid
            Column(modifier = Modifier.weight(0.4f).fillMaxHeight()) {
              ToolDashboardGrid(model = model, isCompact = false)
            }
            
            // Right Selection View Workspace
            Column(
              modifier = Modifier
                .weight(0.6f)
                .fillMaxHeight()
                .padding(bottom = 12.dp)
            ) {
              if (model.activeTool.value == null) {
                WelcomePlaceholderCard(model)
              } else {
                ToolWorkspaceContainer(model)
              }
            }
          }
        } else {
          // MOBILE Single Pane Layout
          Box(modifier = Modifier.fillMaxSize()) {
            if (model.activeTool.value == null) {
              // Main Dashboard
              ToolDashboardGrid(model = model, isCompact = true)
            } else {
              // Expanded Active Tool Workspace Screen
              ToolWorkspaceContainer(model)
            }
          }
        }
      }
    }
  }
}

// --- Welcome card on wide devices when no tool selected ---
@Composable
fun WelcomePlaceholderCard(model: LankaKitViewModel) {
  Card(
    modifier = Modifier
      .fillMaxSize()
      .padding(12.dp),
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(
      containerColor = if (model.isDarkTheme.value) DarkSurface else LightSurface
    )
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(24.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Box(
        modifier = Modifier
          .size(100.dp)
          .clip(CircleShape)
          .background(LankaGold.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = Icons.Default.Widgets,
          contentDescription = "Widgets",
          modifier = Modifier.size(54.dp),
          tint = LankaGold
        )
      }
      Spacer(modifier = Modifier.height(18.dp))
      Text(
        text = if (model.appLanguage.value == "SI") "මෙවලමක් තෝරාගන්න" else "Select a Tool to Begin",
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        color = LankaGold
      )
      Spacer(modifier = Modifier.height(10.dp))
      Text(
        text = if (model.appLanguage.value == "SI") 
          "වඩාත්ම ජනප්‍රිය මෙවලම් 10කින් එකක් තෝරා ඔබේ එදිනෙදා වැඩ කටයුතු පහසු කරගන්න." 
          else "Explore our premium handpicked localized utilities crafted for Sri Lanka.",
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
        color = GrayText,
        modifier = Modifier.padding(horizontal = 24.dp)
      )
    }
  }
}

// --- DASHBOARD TOOL GRID MODEL ---
data class ToolDefinition(
  val id: Int,
  val icon: ImageVector,
  val nameEn: String,
  val nameSi: String,
  val categoryEn: String,
  val categorySi: String,
  val testTag: String,
  val accentColor: Color
)

@Composable
fun ToolDashboardGrid(model: LankaKitViewModel, isCompact: Boolean) {
  val context = LocalContext.current
  val listTools = remember {
    listOf(
      ToolDefinition(0, Icons.Default.CurrencyExchange, "LKR Currency & Gold Rate", "මුදල් සහ රන් මිල", "Economy", "මුල්‍ය පහසුකම්", "currency_rate_tool", LankaGold),
      ToolDefinition(1, Icons.Default.Percent, "Lanka Bill Splitter + Taxes", "බිල් බෙදන්නා (බදු සහිතව)", "Finance", "ගණනය කිරීම්", "bill_splitter_tool", LankaMaroon),
      ToolDefinition(2, Icons.Default.LocalGasStation, "Fuel Cost & Mileage Estimator", "ඉන්ධන සහ ගමන් වියදම්", "Travel", "ගමනාගමනය", "fuel_estimator_tool", LankaOrange),
      ToolDefinition(3, Icons.Default.CalendarToday, "Subha Welawa & Rahu Kalaya", "සුභ වෙලාවල් සහ රාහු කාලය", "Tradition", "සංස්කෘතිය", "rahukala_tool", LankaTeal),
      ToolDefinition(4, Icons.Default.AutoAwesome, "LankaAI Sinhala Assistant", "ලංකා AI සහකාරයා", "Artificial Intelligence", "නවීන තාක්ෂණය", "lanka_ai_tool", LankaGoldDark),
      ToolDefinition(5, Icons.Default.EmojiEmotions, "Sinhala Meme Creator", "මීම් සාදන්නා (Wadan Creator)", "Social", "විනෝදාස්වාදය", "meme_creator_tool", LankaOrange),
      ToolDefinition(6, Icons.Default.Phone, "Emergency Helpline Directory", "හදිසි ඇමතුම් ලැයිස්තුව", "Directory", "ජීවිත බේරාගැනීම්", "emergency_directory_tool", LankaMaroon),
      ToolDefinition(7, Icons.Default.DirectionsBus, "Bus Ticket Fare Estimator", "බස් ගාස්තු ගණකය", "Travel", "ගමනාගමනය", "bus_fare_tool", LankaTeal),
      ToolDefinition(8, Icons.Default.Casino, "Lucky Lottery Draw Simulator", "වාසනා ලොතරැයි රෝදය", "Games & Fun", "විනෝදාස්වාදය", "lotto_games_tool", LankaGreen),
      ToolDefinition(9, Icons.Default.FormatQuote, "Sinhala Viral Wadan status", "සිංහල වදන් සහ කවි", "Fun Quotes", "විනෝදාස්වාදය", "quote_status_tool", LankaTeal)
    )
  }

  // Filter tools based on search query
  val filteredTools = listTools.filter {
    val q = model.searchQuery.value.trim().lowercase()
    q.isEmpty() || 
      it.nameEn.lowercase().contains(q) || 
      it.nameSi.lowercase().contains(q) || 
      it.categoryEn.lowercase().contains(q) || 
      it.categorySi.lowercase().contains(q)
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(12.dp)
  ) {
    // Search Field UI for Instant Filters (highly viral UX)
    OutlinedTextField(
      value = model.searchQuery.value,
      onValueChange = { model.searchQuery.value = it },
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 6.dp)
        .testTag("search_bar"),
      placeholder = {
        Text(
          text = if (model.appLanguage.value == "SI") "මෙවලම් සොයන්න (Search active tools...)" else "Search active tools...",
          fontSize = 13.sp,
          color = GrayText
        )
      },
      leadingIcon = {
        Icon(
          imageVector = Icons.Outlined.Search,
          contentDescription = "Search",
          tint = if (model.isDarkTheme.value) LankaGold else LankaMaroon
        )
      },
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = LankaGold,
        unfocusedBorderColor = if (model.isDarkTheme.value) Color(0xFF222B24) else Color.LightGray,
        focusedContainerColor = if (model.isDarkTheme.value) DarkSurface else LightSurface,
        unfocusedContainerColor = if (model.isDarkTheme.value) DarkSurface else LightSurface
      ),
      shape = RoundedCornerShape(24.dp),
      singleLine = true
    )

    // Featured Live Train Update Status Card (Natural Tones Mockup layout parity)
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 8.dp),
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(
        containerColor = if (model.isDarkTheme.value) Color(0xFF101C15) else Color(0xFFE9F5EF)
      ),
      border = BorderStroke(1.dp, LankaTeal.copy(alpha = 0.25f))
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
              modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(LankaGreen)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = if (model.appLanguage.value == "SI") "සජීවී යාවත්කාලීන • LIVE UPDATE" else "LIVE UPDATE • LANKA STATUS",
              fontSize = 9.sp,
              fontWeight = FontWeight.ExtraBold,
              color = LankaTeal,
              letterSpacing = 1.sp
            )
          }
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = if (model.appLanguage.value == "SI") "කොළඹ සිට මහනුවර දුම්රිය" else "Next Train to Kandy",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (model.isDarkTheme.value) Color.White else LankaMaroon
          )
          Spacer(modifier = Modifier.height(1.dp))
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = "11:30 AM",
              fontSize = 20.sp,
              fontWeight = FontWeight.Black,
              color = LankaGoldDark
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
              modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(LankaOrange.copy(alpha = 0.15f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
              Text(
                text = "EXPRESS",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = LankaOrange
              )
            }
          }
        }

        Box(
          modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (model.isDarkTheme.value) Color(0xFF1E2E24) else Color(0xFFD3EBE0))
            .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
          Text(
            text = if (model.appLanguage.value == "SI") "වේදිකාව 03" else "Platform 03",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = LankaTeal
          )
        }
      }
    }

    // Grid representing the 10 viral tools
    LazyVerticalGrid(
      columns = GridCells.Fixed(if (isCompact) 2 else 1),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      modifier = Modifier.weight(1f)
    ) {
      items(filteredTools) { tool ->
        Card(
          onClick = { model.activeTool.value = tool.id },
          modifier = Modifier
            .fillMaxWidth()
            .testTag(tool.testTag),
          colors = CardDefaults.cardColors(
            containerColor = if (model.isDarkTheme.value) DarkSurface else LightSurface
          ),
          shape = RoundedCornerShape(18.dp),
          border = BorderStroke(
            width = 1.3.dp,
            color = if (model.activeTool.value == tool.id) {
              LankaGold
            } else if (model.isDarkTheme.value) {
              Color(0xFF242533)
            } else {
              Color(0xFFE4E6ED)
            }
          )
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            // Icon Rounded Container
            Box(
              modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tool.accentColor.copy(alpha = if (model.isDarkTheme.value) 0.15f else 0.1f)),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = tool.icon,
                contentDescription = tool.nameEn,
                tint = tool.accentColor,
                modifier = Modifier.size(24.dp)
              )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
              Text(
                text = if (model.appLanguage.value == "SI") tool.nameSi else tool.nameEn,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (model.isDarkTheme.value) Color.White else Color.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
              Spacer(modifier = Modifier.height(2.dp))
              Text(
                text = if (model.appLanguage.value == "SI") tool.categorySi else tool.categoryEn,
                fontSize = 10.sp,
                fontWeight = FontWeight.Light,
                color = if (model.isDarkTheme.value) LightGrayText else Color.Gray,
                maxLines = 1
              )
            }
          }
        }
      }
    }

    // Bottom Poya Day Notice layout matching Natural Tones styling
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = 10.dp),
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(
        containerColor = if (model.isDarkTheme.value) Color(0xFF261D10) else Color(0xFFFEF8F2)
      ),
      border = BorderStroke(1.dp, LankaGold.copy(alpha = 0.25f))
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Box(
          modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(LankaGold.copy(alpha = 0.2f)),
          contentAlignment = Alignment.Center
        ) {
          Text(text = "🌕", fontSize = 16.sp)
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
          Text(
            text = if (model.appLanguage.value == "SI") "මීළඟ පෝය දිනය (Next Poya Day)" else "Next Poya Day",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (model.isDarkTheme.value) LankaGold else Color(0xFF78350F)
          )
          Text(
            text = if (model.appLanguage.value == "SI") "ජුනි 21 - පොසොන් පුර පසළොස්වක පෝය දිනය" else "June 21 - Poson Full Moon Poya Day",
            fontSize = 11.sp,
            color = if (model.isDarkTheme.value) Color.LightGray else Color(0xFF92400E)
          )
        }
      }
    }
  }
}

// --- WORKSPACE MAIN CONTAINER ---
@Composable
fun ToolWorkspaceContainer(model: LankaKitViewModel) {
  val activeIndex = model.activeTool.value ?: return

  Card(
    modifier = Modifier
      .fillMaxSize()
      .padding(12.dp),
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(
      containerColor = if (model.isDarkTheme.value) DarkSurface else LightSurface
    ),
    border = BorderStroke(
      width = 1.5.dp,
      color = if (model.isDarkTheme.value) Color(0xFF29293C) else Color(0xFFE2E4EE)
    )
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      // Header Area with Back button
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(if (model.isDarkTheme.value) Color(0xFF20202C) else Color(0xFFF1F3F6))
          .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        IconButton(
          onClick = { model.activeTool.value = null },
          modifier = Modifier.testTag("back_button")
        ) {
          Icon(
            imageVector = Icons.Default.ArrowBack,
            contentDescription = "Back",
            tint = if (model.isDarkTheme.value) LankaGold else LankaMaroon
          )
        }

        Text(
          text = when (activeIndex) {
            0 -> if (model.appLanguage.value == "SI") "මුදල් සහ රන් මිල (LKR & Gold)" else "LKR & Gold Price Tracker"
            1 -> if (model.appLanguage.value == "SI") "බිල් බෙදන්නා (Lanka Bill Splitter)" else "Lanka Bill Splitter"
            2 -> if (model.appLanguage.value == "SI") "ඉන්ධන වියදම් ගණකය (Fuel Estimator)" else "Fuel Cost Estimator"
            3 -> if (model.appLanguage.value == "SI") "සුභ වෙලාවල් (Subha Welawa)" else "Traditional Subha Welawa"
            4 -> if (model.appLanguage.value == "SI") "ලංකා AI සහකාර (LankaAI)" else "LankaAI Chat Assistant"
            5 -> if (model.appLanguage.value == "SI") "මීම් සාදන්නා (Meme Typography)" else "Sinhala Meme Creator"
            6 -> if (model.appLanguage.value == "SI") "හදිසි ඇමතුම් (Emergency Hotlines)" else "SL Emergency Hotlines"
            7 -> if (model.appLanguage.value == "SI") "බස් ගාස්තු ගණකය (Bus Fair Tracker)" else "Bus Ticket Estimator"
            8 -> if (model.appLanguage.value == "SI") "වාසනා ස්පින් එක (Lotto Lucky Draw)" else "Lotto Lucky Sim Draw"
            9 -> if (model.appLanguage.value == "SI") "සිංහල වදන් සහ කවි (Quotes Suite)" else "Sinhala Quotes Status"
            else -> "Tool Workspace"
          },
          fontSize = 14.sp,
          fontWeight = FontWeight.Bold,
          color = if (model.isDarkTheme.value) Color.White else Color.Black
        )

        // Light ornamental icon representing active state
        Icon(
          imageVector = when (activeIndex) {
            0 -> Icons.Default.CurrencyExchange
            1 -> Icons.Default.Percent
            2 -> Icons.Default.LocalGasStation
            3 -> Icons.Default.CalendarToday
            4 -> Icons.Default.AutoAwesome
            5 -> Icons.Default.EmojiEmotions
            6 -> Icons.Default.Phone
            7 -> Icons.Default.DirectionsBus
            8 -> Icons.Default.Casino
            9 -> Icons.Default.FormatQuote
            else -> Icons.Default.Widgets
          },
          contentDescription = "Active Icon",
          tint = LankaGold,
          modifier = Modifier.size(20.dp)
        )
      }

      // ACTIVE MODULE WORKSPACE CANVAS SIZING (Lazy Scroll content)
      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        item {
          when (activeIndex) {
            0 -> LkrAndGoldPriceWorkspace(model)
            1 -> BillSplitterWorkspace(model)
            2 -> FuelCostEstimatorWorkspace(model)
            3 -> SubhaWelawaWorkspace(model)
            4 -> LankaAiChatWorkspace(model)
            5 -> MemeCreatorWorkspace(model)
            6 -> EmergencyHotlinesWorkspace(model)
            7 -> BusFareCalculatorWorkspace(model)
            8 -> LottoDrawWorkspace(model)
            9 -> SinhalaWadanQuotesWorkspace(model)
          }
        }
      }
    }
  }
}

// ============================================
// TOOL 1: LKR CURRENCY & GOLD TRACKER (FULLY FUNCTIONAL)
// ============================================
@Composable
fun LkrAndGoldPriceWorkspace(model: LankaKitViewModel) {
  val context = LocalContext.current
  val listRates = remember {
    mapOf("USD" to 302.20, "EUR" to 326.50, "GBP" to 382.40, "AUD" to 201.10, "INR" to 3.65, "CAD" to 221.75)
  }

  Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
    // Subsection A: Currency Converter
    Text(
      text = if (model.appLanguage.value == "SI") "💵 LKR විදේශ මුදල් පරිවර්තකය" else "💵 Foreign Currency To LKR Conversion",
      fontSize = 15.sp,
      fontWeight = FontWeight.Bold,
      color = LankaGold
    )

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(
        value = model.crAmountInput.value,
        onValueChange = { model.crAmountInput.value = it },
        label = { Text("Amount") },
        modifier = Modifier.weight(0.6f).testTag("currency_amount_input"),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true
      )

      var expandedMode by remember { mutableStateOf(false) }
      Box(modifier = Modifier.weight(0.4f).padding(top = 8.dp)) {
        Button(
          onClick = { expandedMode = true },
          colors = ButtonDefaults.buttonColors(containerColor = LankaTeal)
        ) {
          Text(text = model.crSelectedCurrency.value)
          Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = "Dropdown")
        }
        DropdownMenu(expanded = expandedMode, onDismissRequest = { expandedMode = false }) {
          listRates.keys.forEach { cur ->
            DropdownMenuItem(
              text = { Text(cur) },
              onClick = {
                model.crSelectedCurrency.value = cur
                expandedMode = false
              }
            )
          }
        }
      }
    }

    // Dynamic output display
    val amt = model.crAmountInput.value.toDoubleOrNull() ?: 1.0
    val rate = listRates[model.crSelectedCurrency.value] ?: 302.20
    val resultLkr = amt * rate

    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = if (model.isDarkTheme.value) Color(0xFF222430) else Color(0xFFF1F4F9))
    ) {
      Column(modifier = Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
          text = if (model.appLanguage.value == "SI") "ශ්‍රී ලංකා රුපියල් (Est. LKR)" else "Conversion Result",
          fontSize = 11.sp,
          color = GrayText
        )
        Text(
          text = "Rs. %,.2f LKR".format(resultLkr),
          fontSize = 22.sp,
          fontWeight = FontWeight.Black,
          color = LankaGreen
        )
        Text(
          text = "Bank buying rate (ඇස්තමේන්තුගත අනුපාතිකය): 1 ${model.crSelectedCurrency.value} = Rs. $rate",
          fontSize = 11.sp,
          color = GrayText
        )
      }
    }

    HorizontalDivider(color = Color.LightGray.copy(alpha = 0.4f))

    // Subsection B: Gold Price Calculator
    Text(
      text = if (model.appLanguage.value == "SI") "🏆 රන් මිල ගණකය (Gold Sovereign Calculator)" else "🏆 Gold Rate Estimator (Sovereign)",
      fontSize = 15.sp,
      fontWeight = FontWeight.Bold,
      color = LankaGold
    )

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(
        value = model.goldSovereigns.value,
        onValueChange = { model.goldSovereigns.value = it },
        label = { Text(if (model.appLanguage.value == "SI") "පවුම් ගණන (Sovereigns)" else "Sovereign Count") },
        modifier = Modifier.weight(0.5f).testTag("gold_count_input"),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true
      )

      Row(modifier = Modifier.weight(0.5f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("22K", "24K").forEach { karat ->
          val selected = model.goldCaratWeight.value == karat
          Button(
            onClick = { model.goldCaratWeight.value = karat },
            colors = ButtonDefaults.buttonColors(
              containerColor = if (selected) LankaGold else Color.Gray.copy(alpha = 0.2f)
            ),
            modifier = Modifier.weight(1f).padding(top = 8.dp)
          ) {
            Text(text = karat, fontSize = 11.sp, color = if (selected) Color.Black else Color.Gray)
          }
        }
      }
    }

    // Typical gold prices in LKR
    val sovCount = model.goldSovereigns.value.toDoubleOrNull() ?: 1.0
    val singleSovereignPrice = if (model.goldCaratWeight.value == "24K") 235500.0 else 215800.0
    val goldPriceTotal = sovCount * singleSovereignPrice

    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = if (model.isDarkTheme.value) Color(0xFF26211E) else Color(0xFFF9F7E8))
    ) {
      Column(modifier = Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
          text = if (model.appLanguage.value == "SI") "රන් ආභරණ මිල ඇස්තමේන්තුව (LKR)" else "Total Gold Price in Sri Lanka",
          fontSize = 11.sp,
          color = GrayText
        )
        Text(
          text = "Rs. %,.2f LKR".format(goldPriceTotal),
          fontSize = 24.sp,
          fontWeight = FontWeight.Black,
          color = LankaGold
        )
        Text(
          text = if (model.appLanguage.value == "SI") 
            "22K පවුමක්:~ Rs. 215,800 | 24K පවුමක්:~ Rs. 235,500 (ආසන්න වෙළඳපල සාමාන්‍යය)"
            else "Est. Rate: 22K ~ Rs. 215,800 | 24K ~ Rs. 235,500 per Sovereign.",
          fontSize = 10.sp,
          color = GrayText,
          textAlign = TextAlign.Center
        )
      }
    }
  }
}

// ============================================
// TOOL 2: LANKA BILL SPLITTER (INCLUDING VAT & SERVICE CHARGES)
// ============================================
@Composable
fun BillSplitterWorkspace(model: LankaKitViewModel) {
  val subtotal = model.bsSubtotal.value.toDoubleOrNull() ?: 0.0
  val people = model.bsPeopleCount.value.toIntOrNull() ?: 1

  // Calculation parameters
  val scAmt = if (model.bsIncludeServiceCharge.value) subtotal * 0.10 else 0.0
  val vatAmt = if (model.bsIncludeVat.value) subtotal * 0.15 else 0.0
  val ssclAmt = if (model.bsIncludeSscl.value) subtotal * 0.025 else 0.0
  val totalBill = subtotal + scAmt + vatAmt + ssclAmt
  val sharePerPerson = if (people > 0) totalBill / people else totalBill

  Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Text(
      text = if (model.appLanguage.value == "SI") "🍽️ ආපනශාලා බිල් බදු කැල්කියුලේටරය" else "🍽️ Sri Lankan Restaurant Bill Divider",
      fontSize = 15.sp,
      fontWeight = FontWeight.Bold,
      color = LankaGold
    )

    OutlinedTextField(
      value = model.bsSubtotal.value,
      onValueChange = { model.bsSubtotal.value = it },
      label = { Text("Subtotal Bill (LKR) / මුළු මුදල") },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      modifier = Modifier.fillMaxWidth().testTag("bill_subtotal_input"),
      singleLine = true
    )

    // Option Checkboxes
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(if (model.isDarkTheme.value) Color(0xFF1E1F2A) else Color(0xFFF7F8FA))
        .padding(10.dp)
        .clip(RoundedCornerShape(8.dp))
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
          checked = model.bsIncludeServiceCharge.value,
          onCheckedChange = { model.bsIncludeServiceCharge.value = it }
        )
        Text(text = "Add Service Charge 10% (සේවා ගාස්තු)", fontSize = 12.sp)
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
          checked = model.bsIncludeVat.value,
          onCheckedChange = { model.bsIncludeVat.value = it }
        )
        Text(text = "Add VAT 15% (වැට් බද්ද)", fontSize = 12.sp)
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
          checked = model.bsIncludeSscl.value,
          onCheckedChange = { model.bsIncludeSscl.value = it }
        )
        Text(text = "Add SSCL 2.5% (සමාජ ආරක්ෂණ දායකත්ව බද්ද)", fontSize = 12.sp)
      }
    }

    OutlinedTextField(
      value = model.bsPeopleCount.value,
      onValueChange = { model.bsPeopleCount.value = it },
      label = { Text(if (model.appLanguage.value == "SI") "පුද්ගලයන් ගණන (People)" else "Number of persons") },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      modifier = Modifier.fillMaxWidth().testTag("bill_people_input"),
      singleLine = true
    )

    // Output Bill Splits
    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = if (model.isDarkTheme.value) Color(0xFF1D2821) else Color(0xFFEDFAF1))
    ) {
      Column(modifier = Modifier.padding(16.dp)) {
        Text(
          text = if (model.appLanguage.value == "SI") "ගෙවීම් සාරාංශය (Bill Summary)" else "Calculated Breakdown",
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
          color = LankaTeal
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
          Text(text = "Subtotal: ", fontSize = 12.sp, color = GrayText)
          Text(text = "Rs. %,.2f".format(subtotal), fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
          Text(text = "Service Charge (10%): ", fontSize = 12.sp, color = GrayText)
          Text(text = "Rs. %,.2f".format(scAmt), fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
          Text(text = "VAT (15%): ", fontSize = 12.sp, color = GrayText)
          Text(text = "Rs. %,.2f".format(vatAmt), fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
          Text(text = "SSCL (2.5%): ", fontSize = 12.sp, color = GrayText)
          Text(text = "Rs. %,.2f".format(ssclAmt), fontSize = 12.sp)
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text(text = "Final Total Bill: ", fontSize = 14.sp, fontWeight = FontWeight.Bold)
          Text(text = "Rs. %,.2f".format(totalBill), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = LankaMaroon)
        }
        Spacer(modifier = Modifier.height(10.dp))
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .background(LankaTeal)
            .padding(12.dp)
            .clip(RoundedCornerShape(8.dp)),
          contentAlignment = Alignment.Center
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
              text = if (model.appLanguage.value == "SI") "එක් අයෙකුට ගෙවීමට ඇති කොටස" else "Each Person Contribution",
              fontSize = 11.sp,
              color = Color.White
            )
            Text(
              text = "Rs. %,.2f LKR".format(sharePerPerson),
              fontSize = 20.sp,
              fontWeight = FontWeight.Black,
              color = LankaGold
            )
          }
        }
      }
    }
  }
}

// ============================================
// TOOL 3: FUEL COST & TRIP ESTIMATOR (TAILORED FOR SRILANKAN COMMUTERS)
// ============================================
@Composable
fun FuelCostEstimatorWorkspace(model: LankaKitViewModel) {
  val vehiclesList = remember {
    listOf(
      Triple("Sedan Car (කාර්)", 14.0, 345.0),
      Triple("Tuk-Tuk (ත්‍රීවීල්)", 30.0, 345.0),
      Triple("Motorbike (යතුරුපැදි)", 50.0, 345.0),
      Triple("Diesel SUV/Van (වෑන්)", 10.0, 320.0),
      Triple("Heavy Bus/Lorry (බස්)", 5.0, 320.0)
    )
  }

  // Find prefilled mileage & price
  val selectedVeh = vehiclesList.find { it.first == model.feSelectedVehicle.value } ?: vehiclesList[0]
  val finalMileage = model.feCustomMileage.value.toDoubleOrNull() ?: selectedVeh.second
  val finalPrice = model.feCustomFuelPrice.value.toDoubleOrNull() ?: selectedVeh.third
  val distance = model.feDistance.value.toDoubleOrNull() ?: 1.0

  val fuelRequired = if (finalMileage > 0) distance / finalMileage else 0.0
  val totalCost = fuelRequired * finalPrice

  Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Text(
      text = if (model.appLanguage.value == "SI") "⛽ ඉන්ධන සහ ගමන් වියදම් ගණක යන්ත්‍රය" else "⛽ Lanka Fuel & Travel Cost Estimator",
      fontSize = 15.sp,
      fontWeight = FontWeight.Bold,
      color = LankaGold
    )

    // Vehicle Dropdown
    var dropdownState by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
      OutlinedButton(
        onClick = { dropdownState = true },
        modifier = Modifier.fillMaxWidth()
      ) {
        Text(text = "Vehicle Type: " + model.feSelectedVehicle.value, color = LankaGold)
        Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = "Dropdown")
      }
      DropdownMenu(expanded = dropdownState, onDismissRequest = { dropdownState = false }) {
        vehiclesList.forEach { v ->
          DropdownMenuItem(
            text = { Text(v.first) },
            onClick = {
              model.feSelectedVehicle.value = v.first
              model.feCustomMileage.value = v.second.toString()
              model.feCustomFuelPrice.value = v.third.toString()
              dropdownState = false
            }
          )
        }
      }
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(
        value = model.feDistance.value,
        onValueChange = { model.feDistance.value = it },
        label = { Text("Trip Distance (km) / දුර") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.weight(1f).testTag("fuel_distance_input"),
        singleLine = true
      )

      OutlinedTextField(
        value = model.feCustomMileage.value,
        onValueChange = { model.feCustomMileage.value = it },
        label = { Text("Avg Mileage (km/L)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.weight(1f).testTag("fuel_mileage_input"),
        singleLine = true
      )
    }

    OutlinedTextField(
      value = model.feCustomFuelPrice.value,
      onValueChange = { model.feCustomFuelPrice.value = it },
      label = { Text("Fuel Price per Litre (LKR) / ලීටරයක මිල") },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      modifier = Modifier.fillMaxWidth().testTag("fuel_price_input"),
      singleLine = true
    )

    // Detailed Output
    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = if (model.isDarkTheme.value) Color(0xFF2B2520) else Color(0xFFFBF4EE))
    ) {
      Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
          text = if (model.appLanguage.value == "SI") "ඇස්තමේන්තුගත මුළු වියදම" else "ESTIMATED TRIP COST",
          fontSize = 11.sp,
          color = GrayText
        )
        Text(
          text = "Rs. %,.2f LKR".format(totalCost),
          fontSize = 24.sp,
          fontWeight = FontWeight.Black,
          color = LankaOrange
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "Fuel Required", fontSize = 10.sp, color = GrayText)
            Text(text = "%,.2f Litres".format(fuelRequired), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LankaGold)
          }
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "Fuel Price", fontSize = 10.sp, color = GrayText)
            Text(text = "Rs. $finalPrice /L", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LankaGold)
          }
        }
      }
    }
  }
}

// ============================================
// TOOL 4: SUBHA WELAWA (DAILY TRADITIONAL AUSPICIOUS HOURS & RAHU KALAYA)
// ============================================
@Composable
fun SubhaWelawaWorkspace(model: LankaKitViewModel) {
  val weekdays = remember {
    listOf("Sunday (ඉරිදා)", "Monday (සඳුදා)", "Tuesday (අඟහරුවාදා)", "Wednesday (බදාදා)", "Thursday (බ්‍රහස්පතින්දා)", "Friday (සිකුරාදා)", "Saturday (සෙනසුරාදා)")
  }

  // Rahu Kalaya and Traditional elements for each day (viral cultural features in SL)
  val traditionalData = remember {
    listOf(
      // Sun
      Triple("04:30 PM - 06:00 PM", "Red, Pink (රතු, රෝස)", "North (උතුර) -> South (දකුණ)"),
      // Mon
      Triple("07:30 AM - 09:00 AM", "White, Cream (සුදු, ක්‍රීම්)", "East (නැගෙනහිර) -> West (බස්නාහිර)"),
      // Tue
      Triple("03:00 PM - 04:30 PM", "Red, Coral (කහ, රතු)", "North-West (වයඹ) -> South-East (ගිනිකොන)"),
      // Wed
      Triple("12:00 PM - 01:30 PM", "Green (කොළ)", "West (බස්නාහිර) -> East (නැගෙනහිර)"),
      // Thu
      Triple("01:30 PM - 03:00 PM", "Golden Yellow (රන්වන් කහ)", "North (උතුර) -> South (දකුණ)"),
      // Fri
      Triple("10:30 AM - 12:00 PM", "Light Blue (ලා නිල්)", "East (නැගෙනහිර) -> West (බස්නාහිර)"),
      // Sat
      Triple("09:00 AM - 10:30 AM", "Dark Blue, Purple (නිල්, දම්)", "South (දකුණ) -> North (උතුර)")
    )
  }

  val selectedIdx = model.selectedDayOfWeek.value
  val item = traditionalData[selectedIdx]

  Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Text(
      text = if (model.appLanguage.value == "SI") "🗓️ දෛනික සුභ වේලාවන් සහ රාහු කාලය" else "🗓️ Daily Traditional Auspicious and Rahu Times",
      fontSize = 15.sp,
      fontWeight = FontWeight.Bold,
      color = LankaGold
    )

    // Horizontal list of weekdays
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      weekdays.forEachIndexed { index, day ->
        val selected = index == selectedIdx
        Card(
          onClick = { model.selectedDayOfWeek.value = index },
          colors = CardDefaults.cardColors(
            containerColor = if (selected) LankaTeal else if (model.isDarkTheme.value) Color(0xFF20202B) else Color(0xFFE4E8EE)
          ),
          shape = RoundedCornerShape(12.dp)
        ) {
          Text(
            text = day.split(" ")[0], // short string
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) Color.White else if (model.isDarkTheme.value) Color.LightGray else Color.DarkGray
          )
        }
      }
    }

    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = if (model.isDarkTheme.value) DarkSurface else LightSurface),
      shape = RoundedCornerShape(16.dp),
      border = BorderStroke(1.dp, LankaTeal.copy(alpha = 0.3f))
    ) {
      Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
          text = weekdays[selectedIdx],
          fontSize = 16.sp,
          fontWeight = FontWeight.Bold,
          color = LankaGold
        )

        HorizontalDivider(color = Color.LightGray.copy(alpha = 0.2f))

        // Rahu Kalaya display (very important culturally)
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .size(36.dp)
              .clip(CircleShape)
              .background(LankaMaroon.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(imageVector = Icons.Default.Warning, contentDescription = "Rahu", tint = LankaMaroon, modifier = Modifier.size(18.dp))
          }
          Spacer(modifier = Modifier.width(12.dp))
          Column {
            Text(text = "Rahu Kalaya (රාහු කාලය - අසුභ කාලය)", fontSize = 11.sp, color = GrayText)
            Text(text = item.first, fontSize = 14.sp, fontWeight = FontWeight.Black, color = LankaMaroon)
          }
        }

        // Daily Lucky Color
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .size(36.dp)
              .clip(CircleShape)
              .background(LankaGreen.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(imageVector = Icons.Default.Palette, contentDescription = "Lucky Color", tint = LankaGreen, modifier = Modifier.size(18.dp))
          }
          Spacer(modifier = Modifier.width(12.dp))
          Column {
            Text(text = "Daily Auspicious Colors (දවසේ සුභ වර්ණයන්)", fontSize = 11.sp, color = GrayText)
            Text(text = item.second, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (model.isDarkTheme.value) Color.White else Color.Black)
          }
        }

        // Maru (spirit movement direction)
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .size(36.dp)
              .clip(CircleShape)
              .background(LankaGold.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(imageVector = Icons.Default.Explore, contentDescription = "Travel", tint = LankaGold, modifier = Modifier.size(18.dp))
          }
          Spacer(modifier = Modifier.width(12.dp))
          Column {
            Text(text = "Maru Direction of Travel (මරු සිටින දිශාව)", fontSize = 11.sp, color = GrayText)
            Text(text = item.third, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (model.isDarkTheme.value) Color.White else Color.Black)
          }
        }
      }
    }
  }
}

// ============================================
// TOOL 5: LANKAAI SECURE GEMINI PROMPT CHAT MODULE (VIRAL AI VIRALITY EXCELLENCE)
// ============================================
@Composable
fun LankaAiChatWorkspace(model: LankaKitViewModel) {
  val coroutineScope = rememberCoroutineScope()
  val clpBoard = LocalClipboardManager.current

  Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Text(
      text = if (model.appLanguage.value == "SI") "🤖 ලංකා AI කතාබහ සහ පරිවර්තකය" else "🤖 LankaAI Chat Translation Buddy",
      fontSize = 15.sp,
      fontWeight = FontWeight.Bold,
      color = LankaGold
    )

    // Preset prompts to make use easy and highly interactive
    val quickPrompts = remember {
      listOf(
        "Write 1-line Sinhala Love status kawi",
        "Explain Sri Lankan tea in 2 sentences",
        "Translate English: 'Can I get a glass of water, please?' to Sinhala singlish",
        "Write a short funny Sinhala joke (විකට කතාවක්)"
      )
    }

    Text(
      text = if (model.appLanguage.value == "SI") "ශීඝ්‍ර Triggers (Tap templates):" else "Quick Start Hotkeys:",
      fontSize = 11.sp,
      color = GrayText
    )

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      quickPrompts.forEach { q ->
        Card(
          onClick = {
            if (!model.isAiRequestRunning.value) {
              model.aiInputText.value = q
              model.isAiRequestRunning.value = true
              model.aiConversationResponse.value = "Generating Answer (AI පිළිතුර සකසමින් පවතී)..."
              coroutineScope.launch {
                val res = GeminiNetwork.askGemini(q)
                model.aiConversationResponse.value = res
                model.isAiRequestRunning.value = false
              }
            }
          },
          colors = CardDefaults.cardColors(
            containerColor = if (model.isDarkTheme.value) Color(0xFF1E1F2A) else Color(0xFFECEFF3)
          )
        ) {
          Text(
            text = q,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            fontSize = 11.sp,
            maxLines = 1,
            color = if (model.isDarkTheme.value) LankaGold else LankaMaroon
          )
        }
      }
    }

    // AI Response View Window
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .height(180.dp),
      colors = CardDefaults.cardColors(
        containerColor = if (model.isDarkTheme.value) Color(0xFF13141C) else Color(0xFFF1F2F6)
      ),
      shape = RoundedCornerShape(12.dp),
      border = BorderStroke(1.dp, if (model.isDarkTheme.value) Color(0xFF262734) else Color.LightGray)
    ) {
      Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        if (model.isAiRequestRunning.value) {
          Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            CircularProgressIndicator(color = LankaGold)
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = "LankaAI thinking...", fontSize = 11.sp, color = GrayText)
          }
        } else {
          Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
              Text(
                text = model.aiConversationResponse.value,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = if (model.isDarkTheme.value) Color.White else Color.Black
              )
            }
            
            // Quick copy option for generated status/translation
            TextButton(
              onClick = {
                clpBoard.setText(AnnotatedString(model.aiConversationResponse.value))
              },
              modifier = Modifier.align(Alignment.End)
            ) {
              Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy text", modifier = Modifier.size(14.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text(text = "Copy", fontSize = 11.sp)
            }
          }
        }
      }
    }

    // Input fields
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      OutlinedTextField(
        value = model.aiInputText.value,
        onValueChange = { model.aiInputText.value = it },
        placeholder = { Text("Ask LankaAI anything in English or Sinhala...") },
        modifier = Modifier.weight(1f).testTag("ai_custom_query_input"),
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
          focusedBorderColor = LankaGold,
          unfocusedBorderColor = Color.LightGray
        )
      )

      Button(
        onClick = {
          val textPrompt = model.aiInputText.value.trim()
          if (textPrompt.isNotEmpty()) {
            model.isAiRequestRunning.value = true
            model.aiConversationResponse.value = "Invoking LankaAI engine (මීළඟ පියවර සූදානම් කරමින් පවතී)..."
            coroutineScope.launch {
              val ans = GeminiNetwork.askGemini(textPrompt)
              model.aiConversationResponse.value = ans
              model.isAiRequestRunning.value = false
            }
          }
        },
        colors = ButtonDefaults.buttonColors(containerColor = LankaGold),
        enabled = !model.isAiRequestRunning.value,
        modifier = Modifier.testTag("ai_custom_query_button")
      ) {
        Icon(imageVector = Icons.Default.Send, contentDescription = "Send prompt", tint = Color.Black)
      }
    }
  }
}

// ============================================
// TOOL 6: SINHALA FUN TYPOGRAPHY MEME CREATOR
// ============================================
@Composable
fun MemeCreatorWorkspace(model: LankaKitViewModel) {
  val listTemplates = remember {
    listOf(
      Brush.linearGradient(colors = listOf(Color(0xFFE65100), Color(0xFFF57C00))), // Saffron Dusk
      Brush.linearGradient(colors = listOf(Color(0xFF004D40), Color(0xFF00897B))), // Ceylon Tea
      Brush.linearGradient(colors = listOf(Color(0xFF311B92), Color(0xFF5E35B1))), // Neon Nights
      Brush.linearGradient(colors = listOf(Color(0xFF1A237E), Color(0xFF0D47A1))), // Indian Ocean
      Brush.linearGradient(colors = listOf(Color(0xFF111115), Color(0xFF262630))) // Minimal Charcoal
    )
  }

  val selectedBrush = listTemplates[model.selectedGradientIndex.value]

  Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Text(
      text = if (model.appLanguage.value == "SI") "🎨 ලංකා මීම් සාදන්නා (Sinhala Meme Designer)" else "🎨 Sinhala Meme & Typography Creator",
      fontSize = 15.sp,
      fontWeight = FontWeight.Bold,
      color = LankaGold
    )

    // Dynamic rendering canvas
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .height(180.dp),
      shape = RoundedCornerShape(12.dp),
      colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(selectedBrush)
          .padding(14.dp)
      ) {
        Column(
          modifier = Modifier.fillMaxSize(),
          verticalArrangement = Arrangement.SpaceBetween,
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Text(
            text = model.memeTopText.value.uppercase(),
            fontSize = model.memeTextScale.value.sp,
            fontWeight = FontWeight.Black,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().shadow(2.dp)
          )
          Text(
            text = model.memeBottomText.value,
            fontSize = (model.memeTextScale.value * 0.9f).sp,
            fontWeight = FontWeight.Bold,
            color = LankaGold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().shadow(2.dp)
          )
        }
      }
    }

    Text(
      text = if (model.appLanguage.value == "SI") "පසුබිම් වර්ණ තෝරන්න (Select style):" else "Template Gradient Backgrounds:",
      fontSize = 12.sp,
      color = GrayText
    )

    // Select templates rows
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      listTemplates.forEachIndexed { idx, brush ->
        val active = model.selectedGradientIndex.value == idx
        Box(
          modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(brush)
            .border(
              width = 2.dp,
              color = if (active) LankaGold else Color.Transparent,
              shape = RoundedCornerShape(6.dp)
            )
            .clickable { model.selectedGradientIndex.value = idx }
        )
      }
    }

    // Slider for Text Size
    Column {
      Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text(text = "Text Font Size", fontSize = 11.sp, color = GrayText)
        Text(text = "${model.memeTextScale.value.toInt()}sp", fontSize = 11.sp, fontWeight = FontWeight.Bold)
      }
      Slider(
        value = model.memeTextScale.value,
        onValueChange = { model.memeTextScale.value = it },
        valueRange = 16f..36f
      )
    }

    // Input fields for Custom Typography
    OutlinedTextField(
      value = model.memeTopText.value,
      onValueChange = { model.memeTopText.value = it },
      label = { Text("Top Header Text / සිංහලෙන්") },
      modifier = Modifier.fillMaxWidth().testTag("meme_top_input"),
      singleLine = true
    )

    OutlinedTextField(
      value = model.memeBottomText.value,
      onValueChange = { model.memeBottomText.value = it },
      label = { Text("Bottom Footer Text / සිංහලෙන්") },
      modifier = Modifier.fillMaxWidth().testTag("meme_bottom_input"),
      singleLine = true
    )
  }
}

// ============================================
// TOOL 7: SRI LANKAN EMERGENCIES HOTLINE DIRECTORY
// ============================================
data class HotlineItem(
  val phone: String,
  val titleEn: String,
  val titleSi: String,
  val subtitleEn: String,
  val subtitleSi: String,
  val icon: ImageVector
)

@Composable
fun EmergencyHotlinesWorkspace(model: LankaKitViewModel) {
  val context = LocalContext.current
  val items = remember {
    listOf(
      HotlineItem("1990", "1990 Suwa Seriya Ambulance", "1990 සුවසැරිය ගිලන්රථ සේවය", "Free high-quality emergency medical response islandwide.", "නොමිලේ ක්‍රියාත්මක වන ප්‍රමුඛතම ගිලන්රථ සේවාව", Icons.Default.MedicalServices),
      HotlineItem("119", "119 Sri Lanka Police Hotline", "119 ශ්‍රී ලංකා පොලිස් හදිසි ඇමතුම්", "National command center for general law enforcement.", "පොලිස් හදිසි සම්බන්ධතා සේවාව", Icons.Default.Shield),
      HotlineItem("110", "110 Fire & Rescue Ambulance", "110 ගිනි නිවීම් හා ගලවා ගැනීමේ සේවය", "Colombo Municipal and regional municipal fire teams.", "හදිසි ගිනිගැනීම් සහ ජීවිත ගලවා ගැනීම්", Icons.Default.LocalFireDepartment),
      HotlineItem("1919", "1919 Government Information", "1919 රාජ්‍ය තොරතුරු කේන්ද්‍රය", "Directory queries, official departments assistance.", "රජයේ සියලුම සේවාවන් පිළිබඳ තොරතුරු", Icons.Default.Info),
      HotlineItem("1912", "1912 Tourist Police Hotline", "1912 සංචාරක පොලිසිය", "Support for foreign tourists and local visitors.", "සංචාරකයින් සඳහා ආධාරක සේවා කවුළුව", Icons.Default.TravelExplore),
      HotlineItem("1992", "1992 Wildlife Rescue Department", "1992 වනජීවී සුරැකුම් දෙපාර්තමේන්තුව", "Report wild animal distress, elephant clashes, etc.", "වල් අලි හෝ වනසතුන්ගෙන් වන පීඩා වාර්තා කිරීමට", Icons.Default.Pets),
      HotlineItem("1901", "1901 Ceylon Electricity Board", "1901 ලංකා විදුලිබල මණ්ඩලය", "Report visual electrical hazards, breakdowns.", "විදුලි බිඳවැටීම් සහ පැමිණිලි අංශය", Icons.Default.Bolt)
    )
  }

  Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Text(
      text = if (model.appLanguage.value == "SI") "🚨 හදිසි සම්බන්ධතා සහ ඇමතුම් සේවාවන්" else "🚨 Fast Dial Sri Lankan Emergency Directory",
      fontSize = 15.sp,
      fontWeight = FontWeight.Bold,
      color = LankaGold
    )

    items.forEach { hotline ->
      Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
          containerColor = if (model.isDarkTheme.value) Color(0xFF1E1E26) else Color(0xFFF2F4F7)
        )
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Row(modifier = Modifier.weight(0.7f)) {
            Box(
              modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(LankaMaroon.copy(alpha = 0.15f)),
              contentAlignment = Alignment.Center
            ) {
              Icon(imageVector = hotline.icon, contentDescription = "Hotline icon", tint = LankaMaroon)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
              Text(
                text = if (model.appLanguage.value == "SI") hotline.titleSi else hotline.titleEn,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (model.isDarkTheme.value) Color.White else Color.Black
              )
              Text(
                text = if (model.appLanguage.value == "SI") hotline.subtitleSi else hotline.subtitleEn,
                fontSize = 10.sp,
                color = GrayText
              )
            }
          }

          // Direct Dialer Trigger Button and TestTag
          Button(
            onClick = {
              try {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${hotline.phone}"))
                context.startActivity(dialIntent)
              } catch (e: Exception) {
                Toast.makeText(context, "Dialer unavailable.", Toast.LENGTH_SHORT).show()
              }
            },
            modifier = Modifier
              .weight(0.3f)
              .testTag("dial_${hotline.phone}"),
            colors = ButtonDefaults.buttonColors(containerColor = LankaMaroon),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
          ) {
            Icon(imageVector = Icons.Default.Phone, contentDescription = "Call", modifier = Modifier.size(12.dp), tint = Color.White)
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = hotline.phone, fontSize = 11.sp, color = Color.White)
          }
        }
      }
    }
  }
}

// ============================================
// TOOL 8: TRANSIT & BUS TICKET PRICE CALCULATOR
// ============================================
@Composable
fun BusFareCalculatorWorkspace(model: LankaKitViewModel) {
  val classes = remember {
    listOf(
      "Normal (සාමාන්‍ය බස්)",
      "Semi-Luxury (අර්ධ සුඛෝපභෝගී)",
      "Luxury / Expressway (සුඛෝපභෝගී අධිවේගී)"
    )
  }

  val distance = model.transitDistance.value.toDoubleOrNull() ?: 0.0

  // Standard official NTC ticket algorithms (base rate Rs. 28, then incremental scale per km)
  val baseFare = when (model.transitSelectedClass.value) {
    classes[1] -> 45.0
    classes[2] -> 80.0
    else -> 28.0
  }

  val multiplier = when (model.transitSelectedClass.value) {
    classes[1] -> 6.50
    classes[2] -> 10.50
    else -> 4.50
  }

  val estimatedTicket = if (distance > 2) baseFare + (distance - 2) * multiplier else baseFare

  Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Text(
      text = if (model.appLanguage.value == "SI") "🚌 ලංකා බස් ගාස්තු ගණනය කිරීම් (NTC)" else "🚌 Sri Lankan Bus Ticket Fare Estimator",
      fontSize = 15.sp,
      fontWeight = FontWeight.Bold,
      color = LankaGold
    )

    // Bus class dropdown
    var expandedDrop by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
      OutlinedButton(
        onClick = { expandedDrop = true },
        modifier = Modifier.fillMaxWidth()
      ) {
        Text(text = "Service: " + model.transitSelectedClass.value, color = LankaGold)
        Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = "Dropdown")
      }
      DropdownMenu(expanded = expandedDrop, onDismissRequest = { expandedDrop = false }) {
        classes.forEach { serviceClass ->
          DropdownMenuItem(
            text = { Text(serviceClass) },
            onClick = {
              model.transitSelectedClass.value = serviceClass
              expandedDrop = false
            }
          )
        }
      }
    }

    OutlinedTextField(
      value = model.transitDistance.value,
      onValueChange = { model.transitDistance.value = it },
      label = { Text("Trip Distance (in Kilometers) / දුර km වලින්") },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      modifier = Modifier.fillMaxWidth().testTag("transit_distance_input"),
      singleLine = true
    )

    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = if (model.isDarkTheme.value) Color(0xFF1E2625) else Color(0xFFEDFAF6))
    ) {
      Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
          text = if (model.appLanguage.value == "SI") "ඇස්තමේන්තුගත සීඝ්‍රගාමී / සාමාන්‍ය ප්‍රවේශපත්‍ර මිල" else "ESTIMATED TICKET PORTION",
          fontSize = 11.sp,
          color = GrayText
        )
        Text(
          text = "Rs. %,.2f LKR".format(estimatedTicket),
          fontSize = 24.sp,
          fontWeight = FontWeight.Black,
          color = LankaTeal
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = if (model.appLanguage.value == "SI") 
            "ජාතික ප්‍රවාහන කොමිෂන් සභාවේ (NTC) නවතම මිල සූත්‍රයන්ට අනුව ගණනය වේ."
            else "Computed using updated National Transport Commission (NTC) price structures.",
          fontSize = 10.sp,
          color = GrayText,
          textAlign = TextAlign.Center
        )
      }
    }
  }
}

// ============================================
// TOOL 9: LANKA DRAW LUCKY LOTTERY DRAW WHEEL SIMULATOR (HIGHLY ENGAGING/VIRAL)
// ============================================
@Composable
fun LottoDrawWorkspace(model: LankaKitViewModel) {
  val letters = remember { listOf("අ", "ඉ", "උ", "ක", "ස", "ජ", "ම", "ර", "ධ", "බ", "හ") }
  val coroutineScope = rememberScope()

  Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Text(
      text = if (model.appLanguage.value == "SI") "🎰 වාසනා මහජන ලොතරැයි රෝදය (Lucky Spin)" else "🎰 SL National Lottery Draw Simulator",
      fontSize = 15.sp,
      fontWeight = FontWeight.Bold,
      color = LankaGold
    )

    Text(
      text = if (model.appLanguage.value == "SI") "ඔබේ වාසනාවන්ත අංක 4 සහ සිංහල අකුර ඇතුලත් කරන්න:" else "Select Your Lucky Letter & 4-Digit Ticket:",
      fontSize = 11.sp,
      color = GrayText
    )

    // User Selection Board
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      // Pick Target Letter
      var expandedLetter by remember { mutableStateOf(false) }
      Box(modifier = Modifier.weight(0.25f)) {
        OutlinedButton(
          onClick = { expandedLetter = true },
          contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
        ) {
          Text(text = model.lottoTargetLetter.value, color = LankaGold, fontSize = 16.sp)
        }
        DropdownMenu(expanded = expandedLetter, onDismissRequest = { expandedLetter = false }) {
          letters.forEach { l ->
            DropdownMenuItem(text = { Text(l) }, onClick = {
              model.lottoTargetLetter.value = l
              expandedLetter = false
            })
          }
        }
      }

      OutlinedTextField(
        value = model.lottoTargetNum1.value,
        onValueChange = { if (it.length <= 2) model.lottoTargetNum1.value = it },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.weight(0.18f),
        singleLine = true
      )
      OutlinedTextField(
        value = model.lottoTargetNum2.value,
        onValueChange = { if (it.length <= 2) model.lottoTargetNum2.value = it },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.weight(0.18f),
        singleLine = true
      )
      OutlinedTextField(
        value = model.lottoTargetNum3.value,
        onValueChange = { if (it.length <= 2) model.lottoTargetNum3.value = it },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.weight(0.18f),
        singleLine = true
      )
      OutlinedTextField(
        value = model.lottoTargetNum4.value,
        onValueChange = { if (it.length <= 2) model.lottoTargetNum4.value = it },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.weight(0.18f),
        singleLine = true
      )
    }

    // SIMULATED DRAWING MACHINE VISUALS
    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = if (model.isDarkTheme.value) Color(0xFF13141C) else Color(0xFFECEDF3)),
      border = BorderStroke(1.2.dp, LankaGold.copy(alpha = 0.6f))
    ) {
      Column(
        modifier = Modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Text(
          text = if (model.isLottoSpinning.value) "🎰 DRAWS REVOLVING..." else "🎰 MAHAJANA SAMPATHA MACHINE",
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          color = LankaGold
        )

        // Machine drawn elements in visual balls
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          // Drawn Letter Sphere
          Box(
            modifier = Modifier
              .size(44.dp)
              .clip(CircleShape)
              .background(if (model.isLottoSpinning.value) LankaOrange else LankaMaroon),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = model.lottoResultLetter.value,
              color = Color.White,
              fontWeight = FontWeight.Black,
              fontSize = 18.sp
            )
          }

          Spacer(modifier = Modifier.width(6.dp))

          // Drawn Numbers Spheres
          model.lottoResultNums.forEach { num ->
            Box(
              modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (model.isLottoSpinning.value) LankaTeal else LankaGoldDull(model.isDarkTheme.value)),
              contentAlignment = Alignment.Center
            ) {
              Text(
                text = num,
                color = if (model.isDarkTheme.value) Color.Black else Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
              )
            }
          }
        }

        Text(
          text = model.lottoResultMessage.value,
          fontSize = 12.sp,
          color = LankaGreen,
          textAlign = TextAlign.Center,
          fontWeight = FontWeight.Bold
        )

        // Action button to start simulation
        Button(
          onClick = {
            if (!model.isLottoSpinning.value) {
              model.isLottoSpinning.value = true
              model.lottoResultMessage.value = "ශ්‍රී ලංකා ජාතික ලොතරැයි රෝදය කරකැවෙමින්..."
              coroutineScope.launch {
                // Spinning animation loop simulations
                repeat(8) { i ->
                  model.lottoResultLetter.value = letters[Random.nextInt(letters.size)]
                  model.lottoResultNums[0] = "%02d".format(Random.nextInt(100))
                  model.lottoResultNums[1] = "%02d".format(Random.nextInt(100))
                  model.lottoResultNums[2] = "%02d".format(Random.nextInt(100))
                  model.lottoResultNums[3] = "%02d".format(Random.nextInt(100))
                  kotlinx.coroutines.delay(120)
                }

                // Final draw results
                val finalLetter = letters[Random.nextInt(letters.size)]
                val fn1 = "%02d".format(Random.nextInt(100))
                val fn2 = "%02d".format(Random.nextInt(100))
                val fn3 = "%02d".format(Random.nextInt(100))
                val fn4 = "%02d".format(Random.nextInt(100))

                model.lottoResultLetter.value = finalLetter
                model.lottoResultNums[0] = fn1
                model.lottoResultNums[1] = fn2
                model.lottoResultNums[2] = fn3
                model.lottoResultNums[3] = fn4

                // Check matches
                val targetNums = listOf(
                  model.lottoTargetNum1.value,
                  model.lottoTargetNum2.value,
                  model.lottoTargetNum3.value,
                  model.lottoTargetNum4.value
                )
                val responseNums = listOf(fn1, fn2, fn3, fn4)

                val matchedLetters = model.lottoTargetLetter.value == finalLetter
                val matchedNumbersCount = targetNums.intersect(responseNums.toSet()).size

                model.lottoResultMessage.value = when {
                  matchedLetters && matchedNumbersCount == 4 -> "🏆 සුපිරි ජයග්‍රහණයක්! ඔබ රිලෝඩ් / මුදල් රු. 20,000,000 දිනාගත්තා!"
                  matchedLetters && matchedNumbersCount > 1 -> "🎉 සුපිරි වාසනාවක්! ඔබ රු. 100,000 දිනාගත්තා!"
                  matchedLetters -> "👍 අකුර ගැළපේ! ඔබට නොමිලේ තවත් ටිකට්පතක් හිමිවේ!"
                  matchedNumbersCount > 0 -> "Matched $matchedNumbersCount numbers: Won Rs. 2,000!"
                  else -> "❌ අහෝ! මෙවර ජයග්‍රහණයක් නැත. නැවත උත්සාහ කරන්න!"
                }
                model.isLottoSpinning.value = false
              }
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = LankaGold),
          enabled = !model.isLottoSpinning.value,
          modifier = Modifier.fillMaxWidth().testTag("simulate_draw_button")
        ) {
          Text(
            text = if (model.isLottoSpinning.value) "REVOLVING..." else "SPIN DRAWS / වාසනාව උරගා බලන්න",
            color = Color.Black,
            fontWeight = FontWeight.ExtraBold
          )
        }
      }
    }
  }
}

// helper color getter
@Composable
fun LankaGoldDull(isDark: Boolean): Color {
  return if (isDark) LankaGold else Color.Gray
}

// scope remember helper
@Composable
fun rememberScope() = rememberCoroutineScope()

// ============================================
// TOOL 10: SINHALA VIRAL WADAN whatsapp status GENERATOR
// ============================================
@Composable
fun SinhalaWadanQuotesWorkspace(model: LankaKitViewModel) {
  val categories = remember {
    listOf(
      "මෝටිවේෂන් (Motivational)",
      "ආදරය (Love Quotes)",
      "මිත්‍රත්වය (Friendship)",
      "හිත නිවන කවි (Buddhist Kawi)"
    )
  }

  // Pre-loaded popular viral quotes (extremely local and shareable)
  val quotesDatabase = remember {
    mapOf(
      categories[0] to listOf(
        "අනෙක් අය අසාර්ථකයි කියන තැනින් ඔබේ ජයග්‍රහණය පටන් ගන්න!",
        "නැවතීමට තැනක් නැත, නැගිටින්න තවත් බොහෝ දුර යා යුතුය.",
        "සමහර පරාජයන් සිදුවන්නේ වඩා හොඳ ජයග්‍රහණයකට මාවත සකසන්නටය.",
        "කවුරු අතහැරියත් තමා කෙරෙහි විශ්වාසය කිසිදිනෙක අත හරින්න එපා."
      ),
      categories[1] to listOf(
        "නුඹේ සිනහව ලඟ මුළු ලෝකයම නතර වූවා සේ දැනේ...",
        "නොලැබෙන බව දැනත් ආදරය කරන්නට හැක්කේ පිවිතුරු සිතකට පමණි.",
        "හැර යන්න දහසක් හේතු තිබෙද්දීත් රඳා පවතින්න සැදෙන එකම හේතුව ආදරයයි.",
        "නුඹ මගේ ලෝකයේ ලස්සනම සිතුවමයි."
      ),
      categories[2] to listOf(
        "මිතුරෙකු සිටීම කෝටියක් සේසත් ලැබුවාටත් වඩා වාසනාවකි.",
        "හොඳ මිතුරන් කිසිදා හැර නොයයි; ඔවුන් ජීවිතයේ මුළු බරම බෙදා ගනී.",
        "සියල්ලෝම මාව විවේචනය කරද්දීත්, ලඟින්ම සිටි මිතුරා සැමදා වටී.",
        "ලඟින් සිටින යාළුවන් කවදාවත් තනිකම පෙන්වන්නේ නැත."
      ),
      categories[3] to listOf(
        "සිත නිවන තැන නිවනයි මිතුරේ, කෝපය හැරලා සැනසේවා හිත...",
        "නොලැබෙන දේවල් පස්සේ නොදුවා, ලැබෙන දෙයින් සැනසෙන්න හුරුවන්න.",
        "ලෝකය වෙනස් කරන්නට පෙර, තමාගේ සිත වෙනස් කරගමු.",
        "අතීතය මඟහැර වර්තමානය තුල සතුටින් සන්සුන්ව ජීවිතය ගෙවන්න."
      )
    )
  }

  val clpManager = LocalClipboardManager.current
  val currentCategory = model.wadanSelectedCategory.value

  Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Text(
      text = if (model.appLanguage.value == "SI") "✍️ සිංහල වෛරල් වදන් සහ කවි සේවා" else "✍️ Sinhala Viral WhatsApp Status Generator",
      fontSize = 15.sp,
      fontWeight = FontWeight.Bold,
      color = LankaGold
    )

    // Category Selector Dropdown Layout
    var expandedCatDrop by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
      OutlinedButton(
        onClick = { expandedCatDrop = true },
        modifier = Modifier.fillMaxWidth()
      ) {
        Text(text = "Category: $currentCategory", color = LankaGold)
        Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = "Dropdown")
      }
      DropdownMenu(expanded = expandedCatDrop, onDismissRequest = { expandedCatDrop = false }) {
        categories.forEach { cat ->
          DropdownMenuItem(
            text = { Text(cat) },
            onClick = {
              model.wadanSelectedCategory.value = cat
              // auto grab a random quote
              val qList = quotesDatabase[cat] ?: emptyList()
              if (qList.isNotEmpty()) {
                model.currentWadanValue.value = qList[Random.nextInt(qList.size)]
              }
              expandedCatDrop = false
            }
          )
        }
      }
    }

    // High fidelity Quote Display Frame Wrapper
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .shadow(4.dp),
      colors = CardDefaults.cardColors(containerColor = if (model.isDarkTheme.value) Color(0xFF1E1F28) else Color(0xFFF7F8FC)),
      border = BorderStroke(1.2.dp, LankaTeal.copy(alpha = 0.5f))
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Icon(
          imageVector = Icons.Default.FormatQuote,
          contentDescription = "Quote marks",
          tint = LankaTeal,
          modifier = Modifier.size(34.dp)
        )

        Text(
          text = model.currentWadanValue.value,
          fontSize = 15.sp,
          fontWeight = FontWeight.Bold,
          textAlign = TextAlign.Center,
          color = if (model.isDarkTheme.value) Color.White else Color.Black,
          lineHeight = 22.sp
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.Center,
          verticalAlignment = Alignment.CenterVertically
        ) {
          // Button to generate next random quote in same category
          Button(
            onClick = {
              val qs = quotesDatabase[currentCategory] ?: emptyList()
              if (qs.isNotEmpty()) {
                model.currentWadanValue.value = qs[Random.nextInt(qs.size)]
              }
            },
            colors = ButtonDefaults.buttonColors(containerColor = LankaGold),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
          ) {
            Icon(imageVector = Icons.Default.Autorenew, contentDescription = "Shuffle", tint = Color.Black, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "Next Wadan (වෙනත් එකක්)", fontSize = 11.sp, color = Color.Black)
          }

          Spacer(modifier = Modifier.width(10.dp))

          // Button to Copy quote directly (critical for WhatsApp virality)
          Button(
            onClick = {
              clpManager.setText(AnnotatedString(model.currentWadanValue.value))
              // also show visual feedback
            },
            modifier = Modifier.testTag("copy_quote_button"),
            colors = ButtonDefaults.buttonColors(containerColor = LankaTeal),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
          ) {
            Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy Status", tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "Copy for WhatsApp", fontSize = 11.sp, color = Color.White)
          }
        }
      }
    }
  }
}
