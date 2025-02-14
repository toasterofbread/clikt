package com.github.ajalt.clikt.core

import com.github.ajalt.clikt.output.HelpFormatter
import com.github.ajalt.clikt.parameters.arguments.Argument
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.ParameterGroup
import com.github.ajalt.clikt.parameters.options.Option
import com.github.ajalt.clikt.parameters.options.eagerOption
import com.github.ajalt.clikt.parameters.options.option

/**
 * A base class for commands that want to define a custom type for their `run` function.
 */
@Suppress("PropertyName")
@ParameterHolderDsl
abstract class BaseCliktCommand<T : BaseCliktCommand<T>>(
    /**
     * The name of the program to use in the help output. If not given, it is inferred from the
     * class name.
     */
    name: String? = null,
) : ParameterHolder {
    /**
     * The name of this command, used in help output.
     *
     * By default, this is the lowercase name of the class, with "Command" removed if it's present
     */
    val commandName: String = name?.takeIf { it.isNotBlank() } ?: inferCommandName()

    /**
     * The help text for this command.
     *
     * ### Example:
     *
     * ```
     * class Command: CliktCommand() {
     *     override fun commandHelp(context: Context): String {
     *         return context.theme.info("This is a command")
     *     }
     * }
     * ```
     */
    open fun help(context: Context): String = ""

    @Deprecated("Method renamed to `help`", ReplaceWith("help(context)"))
    open fun commandHelp(context: Context): String = help(context)

    /**
     * Help text to display at the end of the help output, after any parameters.
     */
    open fun helpEpilog(context: Context): String = ""

    @Deprecated("Method renamed to `helpEpilog`", ReplaceWith("helpEpilog(context)"))
    open fun commandHelpEpilog(context: Context): String = helpEpilog(context)

    /**
     * Used when this command has subcommands, and this command is called without a subcommand. If
     * true, [run] will be called. By default, a [PrintHelpMessage] is thrown instead.
     */
    open val invokeWithoutSubcommand: Boolean = false

    /**
     * If this command is called with no values on the command line, print a help message (by
     * throwing [PrintHelpMessage]) if this is `true`, otherwise run normally.
     */
    open val printHelpOnEmptyArgs: Boolean = false

    /**
     * Extra information about this command to pass to the help formatter.
     */
    open val helpTags: Map<String, String> = emptyMap()

    /**
     * The envvar to use to enable shell autocomplete script generation. Set to null to disable
     * generation.
     */
    open val autoCompleteEnvvar: String? = ""

    /**
     * If true, allow multiple of this command's subcommands to be called sequentially. This will
     * disable `allowInterspersedArgs` on the context of this command and its descendants.
     */
    open val allowMultipleSubcommands: Boolean = false

    /**
     * If true, any options on the command line whose names aren't valid will be parsed as an
     * argument rather than reporting an error. You'll need to define an `argument().multiple()` to
     * collect these options, or an error will still be reported. Unknown short option flags grouped
     * with other flags on the command line will always be reported as errors.
     */
    open val treatUnknownOptionsAsArgs: Boolean = false

    /**
     * If true, don't display this command in help output when used as a subcommand.
     */
    open val hiddenFromHelp: Boolean = false

    internal var _subcommands: List<T> = emptyList()
    internal val _options: MutableList<Option> = mutableListOf()
    internal val _arguments: MutableList<Argument> = mutableListOf()
    private val _groups: MutableList<ParameterGroup> = mutableListOf()
    internal var _contextConfig: Context.Builder.() -> Unit = {}
    private var _context: Context? = null
    private val _messages = mutableListOf<String>()

    private fun registeredOptionNames() = _options.flatMapTo(mutableSetOf()) { it.names }

    /**
     * Create a new context for this command and all its subcommands recursively.
     *
     * @return The new context.
     */
    fun resetContext(parent: Context? = null): Context {
        _context?.close()
        val ctx = Context.build(this, parent, _contextConfig)
        _context = ctx

        if (allowMultipleSubcommands) {
            require(currentContext.ancestors().none { it.command.allowMultipleSubcommands }) {
                "Commands with allowMultipleSubcommands=true cannot be nested in " +
                        "commands that also have allowMultipleSubcommands=true"
            }
        }

        if (currentContext.helpOptionNames.isNotEmpty()) {
            val names = currentContext.helpOptionNames - registeredOptionNames()
            if (names.isNotEmpty()) {
                eagerOption(names, currentContext.localization.helpOptionMessage()) {
                    throw PrintHelpMessage(context)
                }
            }
        }

        val a = ctx.ancestors().map { it.command }.toList()
        for (command in _subcommands) {
            check(command !in a) { "Command ${command.commandName} already registered" }
            command.resetContext(ctx)
        }
        return ctx
    }

    /**
     * Return the parameters that should be sent to the help formatter when this command's [getFormattedHelp] is
     * called.
     */
    open fun allHelpParams(): List<HelpFormatter.ParameterHelp> {
        return listOf(
            _options.mapNotNull { it.parameterHelp(currentContext) },
            _arguments.mapNotNull { it.parameterHelp(currentContext) },
            _groups.mapNotNull { it.parameterHelp(currentContext) },
            _subcommands.mapNotNull { c ->
                HelpFormatter.ParameterHelp.Subcommand(
                    c.commandName,
                    c.shortHelp(currentContext),
                    c.helpTags
                ).takeUnless { c.hiddenFromHelp }
            }
        ).flatten()
    }

    private fun getCommandNameWithParents(): String {
        if (_context == null) resetContext(null)
        return currentContext.commandNameWithParents().joinToString(" ")
    }

    /**
     * This command's context.
     *
     * @throws NullPointerException if accessed before [parse] or [main] are called.
     */
    val currentContext: Context
        get() {
            checkNotNull(_context) { "Context accessed before parse has been called." }
            return _context!!
        }

    /** All messages issued during parsing. */
    val messages: List<String> get() = _messages

    /** Add a message to be printed after parsing */
    fun issueMessage(message: String) {
        _messages += message
    }

    /** The help displayed in the commands list when this command is used as a subcommand. */
    protected fun shortHelp(context: Context): String =
        Regex("""\s*(?:```)?\s*(.+)""").find(help(context))?.groups?.get(1)?.value ?: ""

    /** The names of all direct children of this command */
    fun registeredSubcommandNames(): List<String> = _subcommands.map { it.commandName }

    /**
     * Get a read-only list of commands registered as [subcommands] of this command.
     */
    fun registeredSubcommands(): List<T> = _subcommands.toList()

    /**
     * Get a read-only list of options registered in this command (e.g. via [registerOption] or an
     * [option] delegate)
     */
    fun registeredOptions(): List<Option> = _options.toList()

    /**
     * Get a read-only list of arguments registered in this command (e.g. via [registerArgument] or
     * an [argument] delegate)
     */
    fun registeredArguments(): List<Argument> = _arguments.toList()

    /**
     * Get a read-only list of groups registered in this command (e.g. via [registerOptionGroup] or
     * an [OptionGroup] delegate)
     */
    fun registeredParameterGroups(): List<ParameterGroup> = _groups.toList()

    /**
     * Register an option with this command.
     *
     * This is called automatically for the built-in options, but you need to call this if you want to add a
     * custom option.
     */
    fun registerOption(option: Option) {
        val names = registeredOptionNames()
        for (name in option.names) {
            require(name !in names) { "Duplicate option name $name" }
        }
        if (option.acceptsNumberValueWithoutName) {
            require(_options.none { it.acceptsNumberValueWithoutName }) {
                "Multiple options with acceptsNumberValueWithoutName"
            }
        }
        _options += option
    }

    override fun registerOption(option: GroupableOption) = registerOption(option as Option)

    /**
     * Register an argument with this command.
     *
     * This is called automatically for the built-in arguments, but you need to call this if you want to add a
     * custom argument.
     */
    fun registerArgument(argument: Argument) {
        require(argument.nvalues > 0 || _arguments.none { it.nvalues < 0 }) {
            "Cannot declare multiple arguments with variable numbers of values"
        }
        _arguments += argument
    }

    /**
     * Register a group with this command.
     *
     * This is called automatically for built-in groups, but you need to call this if you want to
     * add a custom group.
     *
     * If you call this manually, you also need to call [registerOption] for any options within the group.
     */
    fun registerOptionGroup(group: ParameterGroup) {
        require(group !in _groups) { "Cannot register the same group twice" }
        require(group.groupName == null || _groups.none { it.groupName == group.groupName }) {
            "Cannot register the same group name twice"
        }
        _groups += group
    }

    /**
     * Return the help string for this command, optionally with an [error].
     *
     * Return `null` if the error does not have a message (e.g. [ProgramResult])
     */
    fun getFormattedHelp(error: CliktError? = null): String? {
        if (error != null && error !is ContextCliktError) {
            return error.message
        }

        val ctx = (error as? ContextCliktError)?.context ?: _context ?: resetContext(null)
        val cmd = ctx.command
        val programName = cmd.getCommandNameWithParents()
        return ctx.helpFormatter(ctx).formatHelp(
            error as? UsageError,
            cmd.help(ctx),
            cmd.helpEpilog(ctx),
            cmd.allHelpParams(),
            programName
        )
    }

    /**
     * Echo the string returned by [getFormattedHelp].
     */
    fun echoFormattedHelp(error: CliktError? = null) {
        val msg = getFormattedHelp(error)
        if (msg != null) {
            echo(msg, err = error?.printError ?: false)
        }
    }

    /**
     * A list of command aliases.
     *
     * If the user enters a command that matches a key in this map, the command is replaced with the
     * corresponding value in the map. The aliases aren't recursive, so aliases won't be looked up again while
     * tokens from an existing alias are being parsed.
     */
    open fun aliases(): Map<String, List<String>> = emptyMap()

    /** Print a line break to `stdout` */
    fun echo() {
        echo("")
    }

    /**
     * Print the [message] to the screen.
     *
     * This is similar to [print] or [println], but converts newlines to the system line separator.
     *
     * @param message The message to print.
     * @param trailingNewline If true, behave like [println], otherwise behave like [print]
     * @param err If true, print to stderr instead of stdout
     */
    fun echo(
        message: Any?,
        trailingNewline: Boolean = true,
        err: Boolean = false,
    ) {
        currentContext.echoer(currentContext, message, trailingNewline, err)
    }

    /**
     * Configure this command's [Context].
     *
     * Context property values are normally inherited from the parent context, but you can override any of them
     * here.
     */
    fun configureContext(block: Context.Builder.() -> Unit) {
        // save the old config to allow multiple context calls
        val c = _contextConfig
        _contextConfig = {
            c()
            block()
        }
    }

    override fun toString() = buildString {
        append("<${this@BaseCliktCommand.classSimpleName()} name=$commandName")

        if (_options.isNotEmpty()) {
            _options.joinTo(this, prefix = " options=[", postfix = "]")
        }

        if (_arguments.isNotEmpty()) {
            _arguments.joinTo(this, prefix = " arguments=[", postfix = "]")
        }

        if (_subcommands.isNotEmpty()) {
            _subcommands.joinTo(this, prefix = " subcommands=[", postfix = "]")
        }

        append(">")
    }
}

/** Add the given commands as a subcommand of this command. */
fun <T : BaseCliktCommand<T>, CommandT : T> CommandT.subcommands(
    commands: Iterable<T>,
): CommandT = apply {
    _subcommands = _subcommands + commands
}

/** Add the given commands as a subcommand of this command. */
fun <T : BaseCliktCommand<T>, CommandT : T> CommandT.subcommands(
    vararg commands: T,
): CommandT = apply {
    _subcommands = _subcommands + commands
}

/**
 * Configure this command's [Context].
 *
 * Context property values are normally inherited from the parent context, but you can override any of them
 * here.
 */
fun <T : BaseCliktCommand<T>, U : T> U.context(block: Context.Builder.() -> Unit): U = apply {
    configureContext(block)
}

private fun Any.classSimpleName(): String = this::class.simpleName.orEmpty().split("$").last()
private fun BaseCliktCommand<*>.inferCommandName(): String {
    val name = classSimpleName()

    val suffixes = setOf("Command", "Commands")
    if (name in suffixes) return name.lowercase()

    val nameWithoutSuffixes = suffixes.fold(name) { acc, s -> acc.removeSuffix(s) }
    val lowerUpperRegex = Regex("([a-z])([A-Z])")

    return nameWithoutSuffixes.replace(lowerUpperRegex) {
        "${it.groupValues[1]}-${it.groupValues[2]}"
    }.lowercase()
}
