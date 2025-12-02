defmodule Turtle do

  # Part 1: Pattern Matching
  # Move a turtle and return the end position, starting at (0, 0) facing north (0, 1).
  # See assignment for a full list of instructions/requirements.
  def move(instructions),
    do: move(instructions, {0, 0}, {0, 1})

  # empty
  defp move([], position, _direction),
    do: {:ok, position}

  # wormhole check (must come early!)
  defp move(rest, {7, 7}, {dx, dy}),
    do: move(rest, {-7, -7}, {-dx, -dy})

  # invalid forward 0
  defp move([{:forward, 0} | _rest], _pos, _dir),
    do: {:err, "invalid forward steps (0)"}

  # forward
  defp move([{:forward, steps} | rest], {x, y}, {dx, dy}),
    do: move(rest, {x + dx * steps, y + dy * steps}, {dx, dy})

  # right
  defp move([{:right} | rest], position, {dx, dy}),
    do: move(rest, position, {dy, -dx})

  # left
  defp move([{:left} | rest], position, {dx, dy}),
    do: move(rest, position, {-dy, dx})

  # jump wormhole invalid
  defp move([{:jump, 7, 7} | _rest], _pos, _dir),
    do: {:err, "invalid jump into wormhole (7, 7)"}

  # jump
  defp move([{:jump, x, y} | rest], _pos, dir),
    do: move(rest, {x, y}, dir)

  # invalid instruction fallback
  defp move([instruction | _rest], _position, _direction),
    do: {:err, "invalid instruction #{inspect instruction}"}

  # Part 2: Pipelining
  # Implement a "quick and dirty" parser that takes lines of labeled instructions
  # (see examples) and returns a Map of parsed functions (%{name, [instructions...]}).
  def parse(input) do
    input
    |> String.split(~r/\r?\n/, trim: true)                # split by newline
    |> Enum.reject(&(String.trim(&1) == "" or String.trim(&1) |> String.starts_with?("#")))  # ignore blanks/comments
    |> Enum.map(&parseLine/1)                             # turn each line into list [name, instruction, args...]
    |> Enum.reduce(%{}, fn [name | instr], acc ->         # group by name
      Map.update(acc, name, [List.to_tuple(instr)], fn old -> old ++ [List.to_tuple(instr)] end)
    end)
  end


  # Parses "name: instruction 1 2 ..." into [:name, :instruction, 1, 2, ...]
  def parseLine(line) do
    [name, instruction | arguments] = line |> String.split(~r{:? }, trim: true)
    [name, instruction]
      |> Enum.map(&String.to_atom/1)
      |> Enum.concat(arguments |> Enum.map(&String.to_integer/1))
  end

end

test = fn (name, test, expected) ->
  try do
    case test.() do
        ^expected -> IO.puts " - #{name}: passed"
        received -> IO.puts " - #{name}:\n    - expected #{inspect expected}\n    - received #{inspect received}"
    end
  rescue
    e -> IO.puts " - #{name}:\n    - expected #{inspect expected}\n    - received #{Exception.message(e)}"
  end
end

IO.puts "Part 1: Pattern Matching"

test.("empty", fn -> Turtle.move [
  #empty
] end, {:ok, {0, 0}})

test.("forward", fn -> Turtle.move [
  {:forward, 1}, # {0, 1}
  {:forward, 2}, # {0, 3}
] end, {:ok, {0, 3}})

test.("forward zero steps", fn -> Turtle.move [
  {:forward, 0}, # error!
] end, {:err, "invalid forward steps (0)"})

test.("right", fn -> Turtle.move [
  {:right}, {:forward, 2}, # {2, 0}
  {:right}, {:right}, {:forward, 3}, # {-1, 0}
] end, {:ok, {-1, 0}})

test.("left", fn -> Turtle.move [
  {:left}, {:forward, 2}, # {-2, 0}
  {:left}, {:left}, {:forward, 3}, # {1, 0}
  {:left}, {:right}, {:forward, 4}, # {5, 0}
] end, {:ok, {5, 0}})

test.("jump", fn -> Turtle.move [
  {:jump, 1, 2}, # (1, 2)
  {:jump, -3, -4}, {:forward, 5}, # (-3, 1)
] end, {:ok, {-3, 1}})

test.("jump wormhole", fn -> Turtle.move [
  {:jump, 7, 7}, # error!
] end, {:err, "invalid jump into wormhole (7, 7)"})

test.("enter wormhole", fn -> Turtle.move [
  {:forward, 7}, # {0, 7}
  {:right}, {:forward, 7}, # {-7, -7} (!)
  {:forward, 1}, # {-8, -7} (!)
] end, {:ok, {-8, -7}})

test.("invalid instruction", fn -> Turtle.move [
  {:invalid}, # error!
] end, {:err, "invalid instruction {:invalid}"})

IO.puts "\nPart 2: Pipelining"

test.("comment", fn -> Turtle.parse """
  #comment
""" end, %{})

test.("function", fn -> Turtle.parse """
  name: instruction
""" end, %{name: [{:instruction}]})

test.("function arguments", fn -> Turtle.parse """
  name: instruction 1 2 3
""" end, %{name: [{:instruction, 1, 2, 3}]})

test.("multiple instructions", fn -> Turtle.parse """
  name: first
  name: second
  name: third
""" end, %{name: [{:first}, {:second}, {:third}]})

test.("multiple functions", fn -> Turtle.parse """
  first: instruction
  second: instruction
  third: instruction
""" end, %{first: [{:instruction}], second: [{:instruction}], third: [{:instruction}]})

test.("move functions (bonus)", fn -> Turtle.move([{:main}], Turtle.parse """
  #zoom zoom!

  uturn: right
  uturn: right

  zoom: forward 1000
  zoom: uturn
  zoom: forward 2000
  zoom: uturn
  zoom: forward 3000

  main: zoom
""") end, {:ok, {0, 2000}})
