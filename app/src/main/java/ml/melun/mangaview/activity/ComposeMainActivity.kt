package ml.melun.mangaview.activity

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ml.melun.mangaview.MainApplication
import ml.melun.mangaview.R
import ml.melun.mangaview.Utils
import ml.melun.mangaview.compose.HomeContent
import ml.melun.mangaview.compose.LoadState
import ml.melun.mangaview.compose.MainViewModel
import ml.melun.mangaview.compose.MangaComposeTheme
import ml.melun.mangaview.compose.SearchContent
import ml.melun.mangaview.compose.TitleImage
import ml.melun.mangaview.mangaview.MTitle
import ml.melun.mangaview.mangaview.MTitle.base_comic
import ml.melun.mangaview.mangaview.MTitle.base_webtoon
import ml.melun.mangaview.mangaview.Title

class ComposeMainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!MainApplication.p.check()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        requestStartupPermissions()
        MainApplication.initDeferredServices()
        setContent {
            MangaComposeTheme(darkTheme = MainApplication.p.darkTheme) {
                ComposeMainApp(
                    viewModel = viewModel,
                    openTitle = { openTitle(it) },
                    openSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    openDownloads = { startActivity(Intent(this, DownloadActivity::class.java)) },
                    openLegacy = { startActivity(Intent(this, MainActivity::class.java)) },
                )
            }
        }
    }

    private fun openTitle(title: MTitle) {
        startActivity(Utils.episodeIntent(this, Title(title)))
    }

    private fun requestStartupPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_DENIED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), MainActivity.PERMISSION_CODE + 1)
        }
    }
}

@Composable
private fun ComposeMainApp(
    viewModel: MainViewModel,
    openTitle: (MTitle) -> Unit,
    openSettings: () -> Unit,
    openDownloads: () -> Unit,
    openLegacy: () -> Unit,
) {
    val home by viewModel.home.collectAsState()
    val search by viewModel.search.collectAsState()
    val library by viewModel.library.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf("홈", "검색", "서재").forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = {},
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            0 -> HomeScreen(home, padding, openTitle, viewModel::refreshHome, openSettings, openDownloads, openLegacy)
            1 -> SearchScreen(search, padding, viewModel::search, openTitle)
            else -> LibraryScreen(library.first, library.second, padding, openTitle, viewModel::refreshLibrary)
        }
    }
}

@Composable
private fun HomeScreen(
    state: LoadState<HomeContent>,
    padding: PaddingValues,
    openTitle: (MTitle) -> Unit,
    refresh: () -> Unit,
    openSettings: () -> Unit,
    openDownloads: () -> Unit,
    openLegacy: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            HeaderActions(refresh, openSettings, openDownloads, openLegacy)
        }
        when (state) {
            LoadState.Loading -> item { CenterMessage(loading = true, text = "불러오는 중") }
            is LoadState.Empty -> item { CenterMessage(text = state.message) }
            is LoadState.Error -> item { CenterMessage(text = state.message) }
            is LoadState.Content -> {
                val content = state.value
                if (content.recent.isNotEmpty()) item { TitleRail("최근 본 작품", content.recent, openTitle) }
                if (content.favorites.isNotEmpty()) item { TitleRail("좋아요", content.favorites, openTitle) }
                items(content.sections, key = { it.title }) { section ->
                    TitleRail(section.title, section.items, openTitle)
                }
            }
        }
    }
}

@Composable
private fun HeaderActions(
    refresh: () -> Unit,
    openSettings: () -> Unit,
    openDownloads: () -> Unit,
    openLegacy: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Manga View", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = refresh) { Text("새로고침") }
            Button(onClick = openDownloads) { Text("다운로드") }
            Button(onClick = openSettings) { Text("설정") }
            Button(onClick = openLegacy) { Text("기존 UI") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                MainApplication.p.baseMode = base_webtoon
                refresh()
            }) { Text("웹툰") }
            Button(onClick = {
                MainApplication.p.baseMode = base_comic
                refresh()
            }) { Text("만화") }
        }
    }
}

@Composable
private fun SearchScreen(
    state: SearchContent,
    padding: PaddingValues,
    onSearch: (String) -> Unit,
    openTitle: (MTitle) -> Unit,
) {
    var query by remember { mutableStateOf(state.query) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                onSearch(it)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("검색") },
        )
        if (state.searching) {
            CenterMessage(loading = true, text = "검색 중")
        } else if (state.message.isNotBlank()) {
            CenterMessage(text = state.message)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(132.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.results, key = { "${it.baseMode}:${it.id}" }) { title ->
                TitleCard(title, openTitle)
            }
        }
    }
}

@Composable
private fun LibraryScreen(
    recent: List<MTitle>,
    favorites: List<MTitle>,
    padding: PaddingValues,
    openTitle: (MTitle) -> Unit,
    refresh: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("서재", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Button(onClick = refresh) { Text("동기화") }
            }
        }
        if (recent.isEmpty() && favorites.isEmpty()) {
            item { CenterMessage(text = "저장된 기록이 없습니다") }
        }
        if (recent.isNotEmpty()) item { TitleRail("최근 본 작품", recent, openTitle) }
        if (favorites.isNotEmpty()) item { TitleRail("좋아요", favorites, openTitle) }
    }
}

@Composable
private fun TitleRail(title: String, items: List<MTitle>, openTitle: (MTitle) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { "${it.baseMode}:${it.id}:${it.name}" }) { item ->
                TitleCard(item, openTitle, Modifier.size(width = 132.dp, height = 214.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TitleCard(title: MTitle, openTitle: (MTitle) -> Unit, modifier: Modifier = Modifier) {
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable { openTitle(title) },
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .background(Color(0xFF202124)),
            ) {
                TitleImage(
                    url = title.thumb,
                    contentDescription = title.name,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(Modifier.padding(8.dp)) {
                Text(title.name ?: "", maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                val meta = listOf(title.baseModeStr, title.author, title.release).filter { it.isNotBlank() }.joinToString(" · ")
                if (meta.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(meta, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun CenterMessage(loading: Boolean = false, text: String) {
    Surface(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp))
            if (loading) Spacer(Modifier.size(12.dp))
            Text(text)
        }
    }
}
