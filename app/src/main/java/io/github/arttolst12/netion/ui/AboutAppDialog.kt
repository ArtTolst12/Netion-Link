package io.github.arttolst12.netion.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.arttolst12.netion.BuildConfig
import io.github.arttolst12.netion.R

@Composable
internal fun AboutAppDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val s = LocalStrings.current
    var linkFailed by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(s.appName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "${s.aboutVersionLabel} ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(s.aboutAppSubtitle, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Copyright © 2026 ArtTolst12",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AboutSection(s.aboutRepoTitle) {
                    AboutBody(s.aboutRepoDesc)
                    OutlinedButton(
                        onClick = {
                            linkFailed = try {
                                context.startActivity(Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/ArtTolst12/Netion-Link")
                                ))
                                false
                            } catch (_: Exception) {
                                true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(s.aboutOpenRepository)
                    }
                    if (linkFailed) {
                        Text(s.aboutLinkError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                AboutSection(s.aboutThanksTitle) {
                    AboutBody(s.aboutThanksDesc)
                    HorizontalDivider()
                    AboutBody(s.aboutTrademarksDesc)
                }
                AboutSection(s.aboutLicenseTitle) { AboutBody(s.aboutLicenseDesc) }
                AboutSection(s.aboutWarrantyTitle) { AboutBody(s.aboutWarrantyDesc) }
                AboutSection(s.aboutPrivacyTitle) { AboutBody(s.aboutPrivacyDesc) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.about_app_mark),
                        contentDescription = null,
                        tint = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f)
                            Color(0xFF757575) else Color(0xFF9E9E9E),
                        modifier = Modifier.size(104.dp)
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(s.close) } }
    )
}

@Composable
private fun AboutSection(title: String, content: @Composable () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() }
            )
            content()
        }
    }
}

@Composable
private fun AboutBody(text: String) {
    SelectionContainer {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
