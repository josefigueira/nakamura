#!/usr/bin/ruby

require 'rubygems'
require 'bundler'
Bundler.setup(:default)
require 'nakamura'
require 'nakamura/users'
include SlingInterface
include SlingUsers

if $ARGV.size < 1
  puts "Usage: delete_groups.rb GROUPNAME [GROUPNAME ...]"
  exit 1
end

@s = Sling.new()
@um = UserManager.new(@s)

$ARGV.each do |group|
  puts @um.delete_group(group)
end

