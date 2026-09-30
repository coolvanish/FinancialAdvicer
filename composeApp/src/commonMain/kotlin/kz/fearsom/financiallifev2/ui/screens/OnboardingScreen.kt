package kz.fearsom.financiallifev2.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kz.fearsom.financiallifev2.i18n.Strings
import kz.fearsom.financiallifev2.ui.icons.LineIcons
import kz.fearsom.financiallifev2.ui.theme.IndigoDark
import kz.fearsom.financiallifev2.ui.theme.IndigoLight
import kz.fearsom.financiallifev2.ui.theme.IndigoPrimary
import kz.fearsom.financiallifev2.ui.theme.LocalAppColors
import kz.fearsom.financiallifev2.ui.theme.MonoFontFamily
import kz.fearsom.financiallifev2.ui.theme.PurpleGradient

// ═══ Intro onboarding — 4-page first-launch flow (design: Onboarding.dc.html) ═══
// Dark-only mock rendered through semantic LocalAppColors so light theme works.
// Chrome: clickable progress dots + Skip on top, swipeable pager, bottom action bar.

private const val PAGE_COUNT = 4
private const val LAST_PAGE = PAGE_COUNT - 1

@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,      // CTA on the last page
    onLoginClick: () -> Unit,  // "already have an account" link
) {
    val colors = LocalAppColors.current
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val isLast = pagerState.targetPage == LAST_PAGE

    fun goTo(page: Int) = scope.launch { pagerState.animateScrollToPage(page) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.backgroundDeep)
    ) {
        // Top indigo glow
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-140).dp)
                .size(380.dp)
                .background(
                    Brush.radialGradient(
                        listOf(IndigoPrimary.copy(alpha = 0.24f), Color.Transparent)
                    )
                )
        )

        Column(modifier = Modifier.fillMaxSize()) {

            // ── Top row: progress dots + Skip ─────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 26.dp, top = 8.dp, end = 26.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(PAGE_COUNT) { i ->
                        val active = pagerState.currentPage == i
                        val dotWidth by animateDpAsState(
                            targetValue = if (active) 22.dp else 7.dp,
                            animationSpec = tween(300),
                            label = "dotWidth"
                        )
                        val dotColor by animateColorAsState(
                            targetValue = if (active) IndigoPrimary else colors.borderStrong,
                            animationSpec = tween(300),
                            label = "dotColor"
                        )
                        Box(
                            modifier = Modifier
                                .height(7.dp)
                                .width(dotWidth)
                                .clip(RoundedCornerShape(99.dp))
                                .background(dotColor)
                                .clickable { goTo(i) }
                        )
                    }
                }

                val skipAlpha by animateFloatAsState(
                    targetValue = if (isLast) 0f else 1f,
                    animationSpec = tween(300),
                    label = "skipAlpha"
                )
                Text(
                    text = Strings.uiOnboardSkip,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textSecondary,
                    modifier = Modifier
                        .alpha(skipAlpha)
                        .clickable(enabled = !isLast) { goTo(LAST_PAGE) }
                )
            }

            // ── Pager ─────────────────────────────────────────────────────────
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { page ->
                when (page) {
                    LAST_PAGE -> PageCta()
                    else -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 26.dp, top = 16.dp, end = 26.dp, bottom = 24.dp)
                    ) {
                        when (page) {
                            0 -> PageStatistics()
                            1 -> PageGame()
                            else -> PagePatterns()
                        }
                    }
                }
            }

            // ── Bottom action bar ─────────────────────────────────────────────
            BottomBar(
                isLast = isLast,
                showBack = pagerState.targetPage > 0,
                onBack = { goTo(pagerState.targetPage - 1) },
                onCta = { if (isLast) onFinish() else goTo(pagerState.targetPage + 1) },
                onLoginClick = onLoginClick
            )
        }
    }
}

// ═══ Page 1 — statistics ═════════════════════════════════════════════════════

@Composable
private fun PageStatistics() {
    val colors = LocalAppColors.current

    SectionLabel(Strings.uiOnboardS1Label)
    Heading(Strings.uiOnboardS1Title)
    Subtitle(Strings.uiOnboardS1Subtitle, bottomPadding = 20.dp)

    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        StatCard(
            barColor = colors.accentNegative,
            labelColor = colors.accentNegativeText,
            label = Strings.uiOnboardS1WorldLabel,
            value = Strings.uiOnboardS1WorldValue,
            unit = Strings.uiOnboardS1WorldUnit,
            desc = Strings.uiOnboardS1WorldDesc
        )
        StatCard(
            barColor = colors.accentWarning,
            labelColor = colors.accentWarningText,
            label = Strings.uiOnboardS1AsiaLabel,
            value = Strings.uiOnboardS1AsiaValue,
            unit = Strings.uiOnboardS1AsiaUnit,
            desc = Strings.uiOnboardS1AsiaDesc
        )
        StatCard(
            barColor = colors.accentNegative,
            labelColor = colors.accentNegativeText,
            label = Strings.uiOnboardS1KzLabel,
            value = Strings.uiOnboardS1KzValue,
            unit = Strings.uiOnboardS1KzUnit,
            desc = Strings.uiOnboardS1KzDesc
        )
    }

    // "4%" recover callout
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.accentNegative.copy(alpha = 0.07f))
            .border(1.dp, colors.accentNegative.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
            .padding(vertical = 13.dp, horizontal = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = Strings.uiOnboardS1RecoverValue,
            fontFamily = MonoFontFamily,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = colors.accentNegative
        )
        Text(
            text = Strings.uiOnboardS1RecoverText,
            fontSize = 12.5.sp,
            lineHeight = 17.5.sp,
            color = colors.textBody,
            modifier = Modifier.weight(1f)
        )
    }

    Text(
        text = Strings.uiOnboardS1Sources,
        fontSize = 10.5.sp,
        lineHeight = 15.sp,
        color = colors.textHint,
        modifier = Modifier.padding(top = 12.dp)
    )
}

@Composable
private fun StatCard(
    barColor: Color,
    labelColor: Color,
    label: String,
    value: String,
    unit: String,
    desc: String
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.backgroundCard)
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .padding(vertical = 15.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(4.dp))
                .background(barColor)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = labelColor
            )
            Spacer(Modifier.height(3.dp))
            Row {
                Text(
                    text = value,
                    fontFamily = MonoFontFamily,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    modifier = Modifier.alignByBaseline()
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = unit,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textEmphasis,
                    modifier = Modifier.alignByBaseline()
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = desc,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                color = colors.textSecondary
            )
        }
    }
}

// ═══ Page 2 — game + education ═══════════════════════════════════════════════

@Composable
private fun PageGame() {
    val colors = LocalAppColors.current

    SectionLabel(Strings.uiOnboardS2Label)
    Heading(Strings.uiOnboardS2Title)
    Subtitle(Strings.uiOnboardS2Subtitle, bottomPadding = 18.dp)

    // Chat mock card
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.backgroundSubCard)
            .border(1.dp, colors.border, RoundedCornerShape(20.dp))
            .padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        // Date chip
        Text(
            text = Strings.uiOnboardS2ChatDate,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textSecondary,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(99.dp))
                .background(colors.backgroundCard)
                .border(1.dp, colors.border, RoundedCornerShape(99.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )

        // Scam bubble
        Box(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 5.dp))
                .background(colors.backgroundCard)
                .border(
                    1.dp,
                    colors.border,
                    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 5.dp)
                )
                .padding(vertical = 11.dp, horizontal = 13.dp)
        ) {
            Text(
                text = Strings.uiOnboardS2ScamMsg,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = colors.textBody
            )
        }

        // Asan advisor tip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(IndigoLight, PurpleGradient))),
                contentAlignment = Alignment.Center
            ) {
                Text("А", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp))
                    .background(colors.bubbleAi)
                    .border(
                        1.dp,
                        colors.bubbleAiBorder,
                        RoundedCornerShape(topStart = 5.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
                    )
                    .padding(vertical = 10.dp, horizontal = 12.dp)
            ) {
                Text(
                    text = boldMarkup(
                        Strings.uiOnboardS2AsanMsg,
                        SpanStyle(color = colors.textPrimary, fontWeight = FontWeight.Bold)
                    ),
                    fontSize = 12.sp,
                    lineHeight = 17.5.sp,
                    color = colors.textEmphasis
                )
            }
        }

        // Answer options
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionRow(
                text = Strings.uiOnboardS2OptWrong,
                badge = Strings.uiOnboardS2OptWrongBadge,
                isRight = false
            )
            OptionRow(
                text = Strings.uiOnboardS2OptRight,
                badge = Strings.uiOnboardS2OptRightBadge,
                isRight = true
            )
        }
    }

    // "10 minutes" fact row
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(IndigoPrimary),
            contentAlignment = Alignment.Center
        ) {
            Text("10", fontFamily = MonoFontFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
        }
        Text(
            text = boldMarkup(
                Strings.uiOnboardS2Fact,
                SpanStyle(color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
            ),
            fontSize = 12.5.sp,
            lineHeight = 17.5.sp,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun OptionRow(text: String, badge: String, isRight: Boolean) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isRight) colors.accentPositive.copy(alpha = 0.08f) else Color.Transparent)
            .border(
                1.dp,
                if (isRight) colors.accentPositive.copy(alpha = 0.45f) else colors.borderStrong,
                RoundedCornerShape(12.dp)
            )
            .padding(vertical = 11.dp, horizontal = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = text, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary)
        Text(
            text = badge,
            fontFamily = if (isRight) null else MonoFontFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (isRight) colors.accentPositive else colors.accentNegative
        )
    }
}

// ═══ Page 3 — patterns ═══════════════════════════════════════════════════════

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PagePatterns() {
    val colors = LocalAppColors.current

    SectionLabel(Strings.uiOnboardS3Label)
    Heading(Strings.uiOnboardS3Title)
    Subtitle(Strings.uiOnboardS3Subtitle, bottomPadding = 18.dp)

    // Pattern chips
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Strings.uiOnboardS3Chips.forEach { chip ->
            Text(
                text = chip,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textEmphasis,
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(colors.backgroundCard)
                    .border(1.dp, colors.borderStrong, RoundedCornerShape(99.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }

    // "90%" callout
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.accentPositive.copy(alpha = 0.08f))
            .border(1.dp, colors.accentPositive.copy(alpha = 0.3f), RoundedCornerShape(18.dp))
            .padding(vertical = 17.dp, horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = Strings.uiOnboardS3StatValue,
            fontFamily = MonoFontFamily,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            color = colors.accentPositive
        )
        Text(
            text = boldMarkup(
                Strings.uiOnboardS3StatText,
                SpanStyle(color = colors.textPrimary, fontWeight = FontWeight.Bold)
            ),
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            color = colors.textBody,
            modifier = Modifier.weight(1f)
        )
    }

    // Note row
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(colors.accentWarning)
        )
        Text(
            text = Strings.uiOnboardS3Note,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            color = colors.textSecondary
        )
    }
}

// ═══ Page 4 — CTA ════════════════════════════════════════════════════════════

@Composable
private fun PageCta() {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(IndigoLight, IndigoPrimary, IndigoDark))),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = LineIcons.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(44.dp)
            )
        }
        Spacer(Modifier.height(26.dp))
        Text(
            text = Strings.uiOnboardS4Label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.5.sp,
            color = IndigoPrimary
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = Strings.uiOnboardS4Title,
            fontSize = 28.sp,
            lineHeight = 33.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.6).sp,
            color = colors.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = Strings.uiOnboardS4Subtitle,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 280.dp)
        )
    }
}

// ═══ Bottom action bar ═══════════════════════════════════════════════════════

@Composable
private fun BottomBar(
    isLast: Boolean,
    showBack: Boolean,
    onBack: () -> Unit,
    onCta: () -> Unit,
    onLoginClick: () -> Unit
) {
    val colors = LocalAppColors.current
    val accountAlpha by animateFloatAsState(
        targetValue = if (isLast) 1f else 0f,
        animationSpec = tween(300),
        label = "accountAlpha"
    )
    val backAlpha by animateFloatAsState(
        targetValue = if (showBack) 1f else 0f,
        animationSpec = tween(300),
        label = "backAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.backgroundPanel)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.divider)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 24.dp, top = 12.dp, end = 24.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            // "Already have an account" link — occupies space always, fades in on last page
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = Strings.uiOnboardHaveAccount,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textSecondary,
                    modifier = Modifier
                        .alpha(accountAlpha)
                        .clickable(enabled = isLast, onClick = onLoginClick)
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .alpha(backAlpha)
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.backgroundCard)
                        .border(1.dp, colors.borderStrong, RoundedCornerShape(16.dp))
                        .clickable(enabled = showBack, onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = LineIcons.ChevronLeft,
                        contentDescription = null,
                        tint = colors.textEmphasis,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(IndigoPrimary)
                        .clickable(onClick = onCta),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isLast) Strings.uiOnboardStart else Strings.uiOnboardNext,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

// ═══ Shared pieces ═══════════════════════════════════════════════════════════

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.5.sp,
        color = IndigoPrimary,
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

@Composable
private fun Heading(text: String) {
    Text(
        text = text,
        fontSize = 27.sp,
        lineHeight = 31.5.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.6).sp,
        color = LocalAppColors.current.textPrimary,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun Subtitle(text: String, bottomPadding: Dp) {
    val colors = LocalAppColors.current
    Text(
        text = boldMarkup(text, SpanStyle(color = colors.textBody)),
        fontSize = 14.sp,
        lineHeight = 21.sp,
        color = colors.textSecondary,
        modifier = Modifier.padding(bottom = bottomPadding)
    )
}

/** Renders `**…**` emphasis spans inside a localized string with [bold] applied. */
private fun boldMarkup(text: String, bold: SpanStyle): AnnotatedString =
    buildAnnotatedString {
        text.split("**").forEachIndexed { i, segment ->
            if (i % 2 == 1) withStyle(bold) { append(segment) } else append(segment)
        }
    }
