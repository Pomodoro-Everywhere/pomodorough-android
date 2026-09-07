package me.egigoka.pomodorough.crash

import io.sentry.SentryOptions

internal object CrashReportingInit {
    fun configure(options: SentryOptions, dsn: String) {
        options.dsn = dsn
        options.beforeSend =
            SentryOptions.BeforeSendCallback { event, _ -> SentryScrubber.scrubEvent(event) }
        options.beforeBreadcrumb =
            SentryOptions.BeforeBreadcrumbCallback { crumb, _ ->
                SentryScrubber.scrubBreadcrumb(crumb)
            }
    }
}
