package dev.studyflow.core.ui.state

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.studyflow.core.designsystem.theme.spacing
import dev.studyflow.core.domain.result.UserMessage

@Composable
public fun LoadingState(
    modifier: Modifier = Modifier,
) {
    StateContainer(modifier) {
        CircularProgressIndicator()
    }
}

@Composable
public fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
) {
    StateContainer(modifier) {
        Text(text = message, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
public fun ErrorState(
    message: UserMessage,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StateContainer(modifier) {
        Text(text = message.copy(), style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onRetry) {
            Text(text = "Try again")
        }
    }
}

@Composable
private fun StateContainer(
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(MaterialTheme.spacing.huge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium, Alignment.CenterVertically),
        content = { content() },
    )
}

private fun UserMessage.copy(): String =
    when (this) {
        UserMessage.Network -> "Check your connection and try again."
        UserMessage.Storage -> "Your study data could not be saved."
        UserMessage.Permission -> "StudyFlow needs permission to complete that action."
        UserMessage.Validation -> "Check the information and try again."
        UserMessage.Unknown -> "Something went wrong. Please try again."
    }
